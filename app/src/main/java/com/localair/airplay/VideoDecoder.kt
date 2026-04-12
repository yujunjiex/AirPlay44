package com.localair.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.localair.airplay.nativebridge.VideoSink
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Async MediaCodec AVC decoder. Same threading pattern as AudioDecoder —
 * callback-driven, dedicated HandlerThread for codec operations. RPiPlay's
 * mirror thread only enqueues; MediaCodec pulls via its own callback when
 * input slots open up. This matches AudioDecoder which is confirmed working
 * on the same hardware, and avoids the 10ms-timeout starve we hit with
 * sync dequeueInputBuffer.
 */
class VideoDecoder(
    private val onFramesChanged: (Boolean) -> Unit = {},
) : VideoSink {

    @Volatile private var codec: MediaCodec? = null
    @Volatile private var surface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var receivedAny = false
    @Volatile private var rendered = 0L
    @Volatile private var fed = 0L

    private val pending = ConcurrentLinkedQueue<Pair<ByteArray, Long>>()
    private val codecThread = HandlerThread("VideoDecoder").apply { start() }
    private val codecHandler = Handler(codecThread.looper)

    fun attach(s: Surface) { surface = s }

    fun release() {
        val c = codec
        codec = null
        codecHandler.post {
            c?.runCatching { stop(); release() }
        }
        codecThread.quitSafely()
        surface = null
        sps = null; pps = null
        pending.clear()
        if (receivedAny) { receivedAny = false; onFramesChanged(false) }
    }

    override fun onNalUnit(data: ByteArray, ptsUs: Long) {
        if (!receivedAny) {
            receivedAny = true
            onFramesChanged(true)
            Log.i(TAG, "first NAL buffer: ${data.size}B, types=${dumpNalTypes(data)}")
        }
        if (codec == null) {
            extractParams(data)
            maybeStartCodec()
        }
        pending.offer(data to ptsUs)
    }

    private fun extractParams(buf: ByteArray) {
        val starts = findStartCodes(buf)
        for (i in starts.indices) {
            val begin = starts[i]
            val end = if (i + 1 < starts.size) starts[i + 1] else buf.size
            if (begin + 4 >= end) continue
            val type = buf[begin + 4].toInt() and 0x1F
            when (type) {
                7 -> if (sps == null) sps = buf.copyOfRange(begin, end).also {
                    Log.i(TAG, "SPS captured, ${it.size}B")
                }
                8 -> if (pps == null) pps = buf.copyOfRange(begin, end).also {
                    Log.i(TAG, "PPS captured, ${it.size}B")
                }
            }
        }
    }

    private fun findStartCodes(buf: ByteArray): List<Int> {
        val out = ArrayList<Int>(4)
        var i = 0
        while (i + 3 < buf.size) {
            if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() &&
                buf[i + 2] == 0.toByte() && buf[i + 3] == 1.toByte()) {
                out.add(i); i += 4
            } else i++
        }
        return out
    }

    private fun dumpNalTypes(buf: ByteArray): String {
        val starts = findStartCodes(buf)
        return starts.joinToString(",") { (buf[it + 4].toInt() and 0x1F).toString() }
    }

    private fun maybeStartCodec() {
        val s = sps ?: return
        val p = pps ?: return
        if (codec != null) return
        val surf = surface ?: return
        codecHandler.post {
            if (codec != null) return@post
            try {
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(s))
                    setByteBuffer("csd-1", ByteBuffer.wrap(p))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                }
                val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                c.setCallback(callback)   // uses codecHandler's looper (we're on it)
                c.configure(fmt, surf, null, 0)
                c.start()
                codec = c
                Log.i(TAG, "MediaCodec configured and started (async)")
            } catch (e: Exception) {
                Log.e(TAG, "codec init failed", e)
            }
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, idx: Int) {
            val entry = pending.poll()
            if (entry == null) {
                // Defer: reschedule ourselves 5ms later with the same slot.
                codecHandler.postDelayed({
                    val late = pending.poll()
                    if (late != null) {
                        val (nal, pts) = late
                        val buf = c.getInputBuffer(idx) ?: return@postDelayed
                        buf.clear(); buf.put(nal)
                        c.queueInputBuffer(idx, 0, nal.size, pts, 0)
                        fed++
                    } else {
                        // No data still; tell codec with an empty buffer so it doesn't block.
                        c.queueInputBuffer(idx, 0, 0, 0, 0)
                    }
                }, 5)
                return
            }
            val (nal, pts) = entry
            val buf = c.getInputBuffer(idx) ?: return
            buf.clear(); buf.put(nal)
            c.queueInputBuffer(idx, 0, nal.size, pts, 0)
            fed++
            if (fed == 1L || fed == 10L || fed % 300 == 0L) {
                Log.i(TAG, "fed=$fed rendered=$rendered queue=${pending.size}")
            }
        }

        override fun onOutputBufferAvailable(c: MediaCodec, idx: Int, info: MediaCodec.BufferInfo) {
            c.releaseOutputBuffer(idx, true)
            rendered++
            if (rendered == 1L || rendered % 60 == 0L) Log.i(TAG, "rendered $rendered frames")
        }

        override fun onOutputFormatChanged(c: MediaCodec, fmt: MediaFormat) {
            Log.i(TAG, "output format: $fmt")
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "codec error: ${e.diagnosticInfo} recoverable=${e.isRecoverable} transient=${e.isTransient}", e)
        }
    }

    companion object { private const val TAG = "VideoDecoder" }
}
