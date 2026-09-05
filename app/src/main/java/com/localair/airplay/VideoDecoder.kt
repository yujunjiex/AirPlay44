package com.localair.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.localair.airplay.nativebridge.VideoSink
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/** Synchronous MediaCodec path; asynchronous callbacks only exist on API 21+. */
class VideoDecoder(private val onFramesChanged: (Boolean) -> Unit = {}) : VideoSink {
    private data class Frame(val bytes: ByteArray, val ptsUs: Long)

    private val pending = ConcurrentLinkedQueue<Frame>()
    @Volatile private var running = true
    @Volatile private var surface: Surface? = null
    @Volatile private var resetRequested = false
    @Volatile var hasFrames = false
        private set
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var codec: MediaCodec? = null

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

    override fun onNalUnit(data: ByteArray, ptsUs: Long) {
        val parameters = H264AnnexB.parameterSets(data)
        parameters.sps?.let { sps = it }
        parameters.pps?.let { pps = it }
        if (H264AnnexB.containsVideoSlice(data)) setHasFrames(true)
        pending.offer(Frame(data, ptsUs))
        while (pending.size > 90) pending.poll()
    }

    override fun onSessionEnd() {
        pending.clear()
        sps = null
        pps = null
        resetRequested = true
        setHasFrames(false)
    }

    fun release() {
        running = false
        worker.interrupt()
        try { worker.join(1000) } catch (_: InterruptedException) {}
        releaseCodec()
        pending.clear()
    }

    private fun decodeLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                if (resetRequested) {
                    resetRequested = false
                    releaseCodec()
                }
                if (codec == null) maybeStartCodec()
                val current = codec
                if (current == null) {
                    Thread.sleep(10)
                    continue
                }

                val inputIndex = current.dequeueInputBuffer(5_000)
                if (inputIndex >= 0) {
                    val frame = pending.poll()
                    if (frame == null) {
                        current.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    } else {
                        val input = current.inputBuffers[inputIndex]
                        input.clear()
                        if (frame.bytes.size <= input.remaining()) {
                            input.put(frame.bytes)
                            current.queueInputBuffer(inputIndex, 0, frame.bytes.size, frame.ptsUs, 0)
                        } else {
                            Log.w(TAG, "dropping oversized NAL ${frame.bytes.size}/${input.capacity()}")
                            current.queueInputBuffer(inputIndex, 0, 0, frame.ptsUs, 0)
                        }
                    }
                }

                var outputIndex = current.dequeueOutputBuffer(info, 0)
                while (outputIndex >= 0) {
                    current.releaseOutputBuffer(outputIndex, true)
                    outputIndex = current.dequeueOutputBuffer(info, 0)
                }
            } catch (interrupted: InterruptedException) {
                // release() wakes the worker.
            } catch (error: Throwable) {
                Log.e(TAG, "decoder failed; waiting for next config", error)
                releaseCodec()
                try { Thread.sleep(250) } catch (_: InterruptedException) {}
            }
        }
    }

    private fun maybeStartCodec() {
        val target = surface ?: return
        val csd0 = sps ?: return
        val csd1 = pps ?: return
        if (!target.isValid) return
        val format = MediaFormat.createVideoFormat(AVC_MIME, 1920, 1080).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
        }
        val decoder = MediaCodec.createDecoderByType(AVC_MIME)
        decoder.configure(format, target, null, 0)
        decoder.start()
        codec = decoder
        Log.i(TAG, "H.264 decoder started")
    }

    private fun releaseCodec() {
        val current = codec ?: return
        codec = null
        try { current.stop() } catch (_: Throwable) {}
        try { current.release() } catch (_: Throwable) {}
    }

    private fun setHasFrames(value: Boolean) {
        if (hasFrames == value) return
        hasFrames = value
        onFramesChanged(value)
    }

    companion object {
        private const val TAG = "AirPlay44-Video"
        private const val AVC_MIME = "video/avc"
    }
}
