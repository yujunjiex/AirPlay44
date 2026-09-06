package com.localair.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.localair.airplay.nativebridge.VideoSink
import java.nio.ByteBuffer
import java.util.ArrayDeque

/** Synchronous MediaCodec path; asynchronous callbacks only exist on API 21+. */
internal class VideoDecoder(
    private val onFramesChanged: (Boolean) -> Unit = {},
    private val onVideoSizeChanged: (PixelSize) -> Unit = {},
    private val onSessionExpired: () -> Unit = {},
) : VideoSink {
    private data class Frame(
        val bytes: ByteArray,
        val ptsUs: Long,
    )

    private val pending = ArrayDeque<Frame>()
    private val pendingLock = Any()
    private val sessionLock = Any()
    private val presentationClock = PresentationClock()
    @Volatile private var running = true
    @Volatile private var surface: Surface? = null
    @Volatile private var resetRequested = false
    @Volatile private var waitingForIdr = false
    @Volatile private var sessionEndDeadlineNs = 0L
    @Volatile private var lastNalReceivedNs = 0L
    @Volatile private var queueSaturated = false
    @Volatile var hasFrames = false
        private set
    @Volatile var displaySize: PixelSize? = null
        private set
    @Volatile private var geometry: StreamGeometry? = null
    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null
    @Volatile private var codec: MediaCodec? = null
    private var codecStartedNs = 0L
    private var lastOutputNs = 0L
    private var inFlightVideoFrames = 0
    private var inFlightSinceNs = 0L

    private val worker = Thread({ decodeLoop() }, "AirPlay44-Video").apply { start() }

    fun attachSurface(value: Surface) {
        surface = value
        resetRequested = true
    }

    fun detachSurface() {
        surface = null
        resetRequested = true
        setHasFrames(false)
    }

    override fun onNalUnit(
        data: ByteArray,
        ptsUs: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
    ) {
        synchronized(sessionLock) {
            sessionEndDeadlineNs = 0L
            lastNalReceivedNs = System.nanoTime()
        }
        updateGeometry(sourceWidth, sourceHeight, videoWidth, videoHeight)

        // One Annex-B pass per access unit keeps JNI receive work small on the
        // TV's dual-core CPU.
        val inspection = H264AnnexB.inspect(data)
        val parameters = inspection.parameterSets
        val configChanged =
            parameters.sps?.let { sps?.contentEquals(it) == false } == true ||
                parameters.pps?.let { pps?.contentEquals(it) == false } == true
        parameters.sps?.let { sps = it }
        parameters.pps?.let { pps = it }
        if (configChanged) {
            clearPending()
            resetRequested = true
            presentationClock.reset()
            waitingForIdr = waitingForIdr || codec != null
        }

        val hasIdr = inspection.hasIdr
        val hasVideoSlice = inspection.hasVideoSlice
        if (!hasVideoSlice) return
        if (waitingForIdr && !hasIdr) return
        if (waitingForIdr) {
            clearPending()
            resetRequested = true
            presentationClock.reset()
            waitingForIdr = false
            Log.i(TAG, "resynchronizing decoder at IDR frame")
        }
        if (queueSaturated) {
            if (!hasIdr) return
            clearPending()
            offerPending(Frame(data, ptsUs))
            resetRequested = true
            presentationClock.reset()
            queueSaturated = false
            Log.i(TAG, "decoder queue resynchronized at IDR frame")
            return
        }

        offerPending(Frame(data, ptsUs))
        if (pendingSize() > MAX_PENDING_FRAMES) {
            if (hasIdr) {
                clearPending()
                offerPending(Frame(data, ptsUs))
                resetRequested = true
                presentationClock.reset()
                queueSaturated = false
                Log.w(TAG, "decoder queue is behind; restarting from current IDR")
            } else {
                // Preserve the already accepted dependency chain and reject
                // only new delta frames until an IDR arrives. Clearing a small
                // startup queue made slow Android 4.4 decoders enter a
                // permanent black-screen/keyframe loop.
                removeNewestPending()
                if (!queueSaturated) {
                    queueSaturated = true
                    Log.w(TAG, "decoder queue saturated; preserving chain until next IDR")
                }
            }
        }
    }

    override fun onSessionEnd() {
        // iOS can briefly tear down every RTSP control connection while
        // changing playback state. A new NAL cancels this delayed end so a
        // pause, full-screen transition, or quick reconnect keeps its frame.
        synchronized(sessionLock) {
            sessionEndDeadlineNs = System.nanoTime() + SESSION_END_GRACE_NS
        }
        Log.i(TAG, "AirPlay controls closed; waiting for reconnect grace period")
    }

    private fun endSessionNow(notifyExpired: Boolean = true) {
        clearPending()
        sps = null
        pps = null
        geometry = null
        displaySize = null
        waitingForIdr = false
        queueSaturated = false
        resetRequested = true
        presentationClock.reset()
        setHasFrames(false)
        Log.i(TAG, "AirPlay session ended after reconnect grace period")
        if (notifyExpired) onSessionExpired()
    }

    fun resetForReceiverRestart() {
        synchronized(sessionLock) {
            sessionEndDeadlineNs = 0L
            endSessionNow(notifyExpired = false)
        }
    }

    fun release() {
        running = false
        worker.interrupt()
        try {
            worker.join(1000)
        } catch (_: InterruptedException) {
        }
        releaseCodec()
        clearPending()
    }

    private fun updateGeometry(
        sourceWidth: Int,
        sourceHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
    ) {
        if (sourceWidth <= 0 && sourceHeight <= 0 && videoWidth <= 0 && videoHeight <= 0) return
        val updated = VideoGeometry.fromAirPlay(sourceWidth, sourceHeight, videoWidth, videoHeight)
        if (updated == geometry) return
        val hadActiveDecoder = codec != null
        geometry = updated
        displaySize = updated.display
        clearPending()
        resetRequested = true
        presentationClock.reset()
        if (hadActiveDecoder) waitingForIdr = true
        queueSaturated = false
        onVideoSizeChanged(updated.display)
        Log.i(TAG, "AirPlay geometry: display=${updated.display}, encoded=${updated.encoded}")
    }

    private fun decodeLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                finishSessionEndIfExpired()
                if (resetRequested) {
                    resetRequested = false
                    releaseCodec()
                    presentationClock.reset()
                    inFlightVideoFrames = 0
                }
                if (codec == null) maybeStartCodec()
                val current = codec
                if (current == null) {
                    Thread.sleep(10)
                    continue
                }

                var didWork = drainOutput(current, info)
                if (pendingSize() > 0) {
                    val inputIndex = current.dequeueInputBuffer(5_000)
                    if (inputIndex >= 0) {
                        val frame = pollPending()
                        if (frame != null) {
                            val input = current.inputBuffers[inputIndex]
                            input.clear()
                            if (frame.bytes.size <= input.remaining()) {
                                input.put(frame.bytes)
                                current.queueInputBuffer(inputIndex, 0, frame.bytes.size, frame.ptsUs, 0)
                                if (inFlightVideoFrames == 0) inFlightSinceNs = System.nanoTime()
                                inFlightVideoFrames++
                            } else {
                                Log.w(TAG, "dropping oversized NAL ${frame.bytes.size}/${input.capacity()}")
                                current.queueInputBuffer(inputIndex, 0, 0, frame.ptsUs, 0)
                            }
                            didWork = true
                        }
                    }
                }
                recoverIfDecoderStalled()
                if (!didWork) Thread.sleep(2)
            } catch (interrupted: InterruptedException) {
                // release() or a reset wakes the worker.
            } catch (error: Throwable) {
                Log.e(TAG, "decoder failed; waiting for next config", error)
                recoverAtNextIdr("decoder exception")
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    private fun drainOutput(current: MediaCodec, info: MediaCodec.BufferInfo): Boolean {
        var didWork = false
        var outputIndex = current.dequeueOutputBuffer(info, 0)
        while (outputIndex >= 0) {
            if (pendingSize() <= MAX_SCHEDULED_BACKLOG) {
                waitForPresentation(info.presentationTimeUs)
            } else {
                // Decode and render immediately until the receiver catches up.
                presentationClock.reset()
            }
            current.releaseOutputBuffer(outputIndex, true)
            lastOutputNs = System.nanoTime()
            if (inFlightVideoFrames > 0) {
                inFlightVideoFrames--
                inFlightSinceNs = if (inFlightVideoFrames == 0) 0L else lastOutputNs
            }
            setHasFrames(true)
            didWork = true
            outputIndex = current.dequeueOutputBuffer(info, 0)
        }
        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            reportDecoderFormat(current.outputFormat)
            didWork = true
        }
        return didWork
    }

    private fun waitForPresentation(ptsUs: Long) {
        val targetNs = presentationClock.targetTimeNs(ptsUs, System.nanoTime())
        while (running) {
            val remainingNs = targetNs - System.nanoTime()
            if (remainingNs <= 0) return
            if (remainingNs > 2_000_000L) {
                val sleepMs = ((remainingNs - 1_000_000L) / 1_000_000L).coerceAtLeast(1L)
                Thread.sleep(sleepMs)
            } else {
                Thread.yield()
            }
        }
    }

    private fun reportDecoderFormat(format: MediaFormat) {
        Log.i(TAG, "output format: $format")
        val hasCrop = format.containsKey("crop-left") && format.containsKey("crop-right") &&
            format.containsKey("crop-top") && format.containsKey("crop-bottom")
        // Some Android 4.4 codecs expose the padded coded buffer (for example
        // 1920x1088) as width/height. Only let the decoder override valid
        // AirPlay geometry when it also supplies an explicit visible crop.
        if (geometry != null && !hasCrop) return
        val width = visibleDimension(format, MediaFormat.KEY_WIDTH, "crop-left", "crop-right")
        val height = visibleDimension(format, MediaFormat.KEY_HEIGHT, "crop-top", "crop-bottom")
        val size = PixelSize(width, height)
        if (!size.isValid || size == displaySize) return
        displaySize = size
        onVideoSizeChanged(size)
    }

    private fun visibleDimension(format: MediaFormat, key: String, cropStart: String, cropEnd: String): Int {
        return try {
            if (format.containsKey(cropStart) && format.containsKey(cropEnd)) {
                format.getInteger(cropEnd) - format.getInteger(cropStart) + 1
            } else {
                format.getInteger(key)
            }
        } catch (_: Throwable) {
            0
        }
    }

    private fun maybeStartCodec() {
        val target = surface ?: return
        val csd0 = sps ?: return
        val csd1 = pps ?: return
        if (!target.isValid) return
        val encoded = geometry?.encoded ?: VideoGeometry.fromAirPlay(0, 0, 0, 0).encoded
        val format = MediaFormat.createVideoFormat(AVC_MIME, encoded.width, encoded.height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
        }
        val decoder = MediaCodec.createDecoderByType(AVC_MIME)
        decoder.configure(format, target, null, 0)
        decoder.start()
        codec = decoder
        codecStartedNs = System.nanoTime()
        lastOutputNs = 0L
        inFlightVideoFrames = 0
        inFlightSinceNs = 0L
        Log.i(TAG, "H.264 decoder started at ${encoded.width}x${encoded.height}")
    }

    private fun releaseCodec() {
        val current = codec ?: return
        codec = null
        try {
            current.stop()
        } catch (_: Throwable) {
        }
        try {
            current.release()
        } catch (_: Throwable) {
        }
        codecStartedNs = 0L
        lastOutputNs = 0L
        inFlightVideoFrames = 0
        inFlightSinceNs = 0L
    }

    private fun recoverIfDecoderStalled() {
        if (!hasFrames || codec == null || inFlightVideoFrames <= 0) return
        val now = System.nanoTime()
        val reference = maxOf(codecStartedNs, lastOutputNs, inFlightSinceNs)
        if (reference <= 0L || now - reference < DECODER_STALL_NS) return
        // Do not treat a genuine sender pause as a decoder stall.
        if (now - lastNalReceivedNs >= DECODER_STALL_NS) return
        recoverAtNextIdr("no decoded output for ${DECODER_STALL_NS / 1_000_000L} ms")
    }

    private fun recoverAtNextIdr(reason: String) {
        Log.w(TAG, "$reason; restarting at next IDR")
        releaseCodec()
        clearPending()
        presentationClock.reset()
        waitingForIdr = true
        queueSaturated = false
    }

    private fun finishSessionEndIfExpired() {
        synchronized(sessionLock) {
            val deadline = sessionEndDeadlineNs
            if (deadline == 0L || System.nanoTime() < deadline) return
            sessionEndDeadlineNs = 0L
            endSessionNow()
        }
    }

    private fun offerPending(frame: Frame) = synchronized(pendingLock) { pending.offerLast(frame) }

    private fun pollPending(): Frame? = synchronized(pendingLock) { pending.pollFirst() }

    private fun removeNewestPending(): Frame? = synchronized(pendingLock) { pending.pollLast() }

    private fun pendingSize(): Int = synchronized(pendingLock) { pending.size }

    private fun clearPending() = synchronized(pendingLock) { pending.clear() }

    private fun setHasFrames(value: Boolean) {
        if (hasFrames == value) return
        hasFrames = value
        onFramesChanged(value)
    }

    companion object {
        private const val TAG = "AirPlay44-Video"
        private const val AVC_MIME = "video/avc"
        private const val MAX_PENDING_FRAMES = 45
        private const val MAX_SCHEDULED_BACKLOG = 1
        private const val DECODER_STALL_NS = 8_000_000_000L
        private const val SESSION_END_GRACE_NS = 3_000_000_000L
    }
}
