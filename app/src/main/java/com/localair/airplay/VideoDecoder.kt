package com.localair.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.localair.airplay.nativebridge.VideoSink
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/** Synchronous MediaCodec path; asynchronous callbacks only exist on API 21+. */
internal class VideoDecoder(
    private val onFramesChanged: (Boolean) -> Unit = {},
    private val onVideoSizeChanged: (PixelSize) -> Unit = {},
) : VideoSink {
    private data class Frame(val bytes: ByteArray, val ptsUs: Long)

    private val pending = ConcurrentLinkedQueue<Frame>()
    private val presentationClock = PresentationClock()
    @Volatile private var running = true
    @Volatile private var surface: Surface? = null
    @Volatile private var resetRequested = false
    @Volatile private var waitingForIdr = false
    @Volatile var hasFrames = false
        private set
    @Volatile var displaySize: PixelSize? = null
        private set
    @Volatile private var geometry: StreamGeometry? = null
    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null
    @Volatile private var codec: MediaCodec? = null

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
        updateGeometry(sourceWidth, sourceHeight, videoWidth, videoHeight)

        val parameters = H264AnnexB.parameterSets(data)
        val configChanged =
            parameters.sps?.let { sps?.contentEquals(it) == false } == true ||
                parameters.pps?.let { pps?.contentEquals(it) == false } == true
        parameters.sps?.let { sps = it }
        parameters.pps?.let { pps = it }
        if (configChanged) {
            pending.clear()
            resetRequested = true
            presentationClock.reset()
        }

        val hasIdr = H264AnnexB.containsIdr(data)
        if (waitingForIdr && !hasIdr) return
        if (hasIdr) waitingForIdr = false

        pending.offer(Frame(data, ptsUs))
        if (pending.size > MAX_PENDING_FRAMES) {
            pending.clear()
            resetRequested = true
            presentationClock.reset()
            waitingForIdr = !hasIdr
            if (hasIdr) pending.offer(Frame(data, ptsUs))
            Log.w(TAG, "decoder queue overflow; resynchronizing at the next IDR frame")
        }
    }

    override fun onSessionEnd() {
        pending.clear()
        sps = null
        pps = null
        geometry = null
        displaySize = null
        waitingForIdr = false
        resetRequested = true
        presentationClock.reset()
        setHasFrames(false)
    }

    fun release() {
        running = false
        worker.interrupt()
        try {
            worker.join(1000)
        } catch (_: InterruptedException) {
        }
        releaseCodec()
        pending.clear()
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
        geometry = updated
        displaySize = updated.display
        pending.clear()
        resetRequested = true
        presentationClock.reset()
        onVideoSizeChanged(updated.display)
        Log.i(TAG, "AirPlay geometry: display=${updated.display}, encoded=${updated.encoded}")
    }

    private fun decodeLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                if (resetRequested) {
                    resetRequested = false
                    releaseCodec()
                    presentationClock.reset()
                    setHasFrames(false)
                }
                if (codec == null) maybeStartCodec()
                val current = codec
                if (current == null) {
                    Thread.sleep(10)
                    continue
                }

                var didWork = drainOutput(current, info)
                if (pending.peek() != null) {
                    val inputIndex = current.dequeueInputBuffer(5_000)
                    if (inputIndex >= 0) {
                        val frame = pending.poll()
                        if (frame != null) {
                            val input = current.inputBuffers[inputIndex]
                            input.clear()
                            if (frame.bytes.size <= input.remaining()) {
                                input.put(frame.bytes)
                                current.queueInputBuffer(inputIndex, 0, frame.bytes.size, frame.ptsUs, 0)
                            } else {
                                Log.w(TAG, "dropping oversized NAL ${frame.bytes.size}/${input.capacity()}")
                                current.queueInputBuffer(inputIndex, 0, 0, frame.ptsUs, 0)
                            }
                            didWork = true
                        }
                    }
                }
                if (!didWork) Thread.sleep(2)
            } catch (interrupted: InterruptedException) {
                // release() or a reset wakes the worker.
            } catch (error: Throwable) {
                Log.e(TAG, "decoder failed; waiting for next config", error)
                releaseCodec()
                presentationClock.reset()
                waitingForIdr = true
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
            waitForPresentation(info.presentationTimeUs)
            current.releaseOutputBuffer(outputIndex, true)
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
        if (geometry != null) return
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
    }

    private fun setHasFrames(value: Boolean) {
        if (hasFrames == value) return
        hasFrames = value
        onFramesChanged(value)
    }

    companion object {
        private const val TAG = "AirPlay44-Video"
        private const val AVC_MIME = "video/avc"
        private const val MAX_PENDING_FRAMES = 45
    }
}
