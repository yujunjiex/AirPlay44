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
 * Async MediaCodec AVC decoder that lives in the Service, independent of
 * Activity lifecycle. Always set as the video sink so SPS+PPS are never
 * missed. The Activity provides/revokes a Surface — codec is only created
 * when both SPS+PPS AND a Surface are present, and torn down when the
 * Surface goes away.
 */
class VideoDecoder(
    private val onFramesChanged: (Boolean) -> Unit = {},
) : VideoSink {

    @Volatile private var codec: MediaCodec? = null
    @Volatile private var surface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    @Volatile var hasFrames = false; private set
    @Volatile private var rendered = 0L
    @Volatile private var fed = 0L

    private val pending = ConcurrentLinkedQueue<Pair<ByteArray, Long>>()
    private val codecThread = HandlerThread("VideoDecoder").apply { start() }
    private val codecHandler = Handler(codecThread.looper)

    fun attachSurface(s: Surface) {
        surface = s
        Log.i(TAG, "surface attached")
        codecHandler.post { maybeStartCodec() }
    }

    fun detachSurface() {
        surface = null
        Log.i(TAG, "surface detached — stopping codec")
        val c = codec
        codec = null
        codecHandler.post { c?.runCatching { stop(); release() } }
        pending.clear()
        rendered = 0; fed = 0
        if (hasFrames) { hasFrames = false; onFramesChanged(false) }
    }

    fun release() {
        detachSurface()
        codecThread.quitSafely()
        sps = null; pps = null
    }

    override fun onSessionEnd() {
        Log.i(TAG, "session ended — resetting")
        val c = codec
        codec = null
        codecHandler.post { c?.runCatching { stop(); release() } }
        sps = null; pps = null
        pending.clear()
        rendered = 0; fed = 0
        if (hasFrames) { hasFrames = false; onFramesChanged(false) }
    }

    override fun onNalUnit(data: ByteArray, ptsUs: Long) {
        extractParams(data)
        if (!hasFrames && sps != null && pps != null) {
            hasFrames = true
            onFramesChanged(true)
            Log.i(TAG, "first NAL with config: ${data.size}B, types=${dumpNalTypes(data)}")
        }
        // Always buffer — codec may not be running yet (waiting for Surface).
        // Cap at ~120 frames (~2s at 60fps) to avoid unbounded growth.
        pending.offer(data to ptsUs)
        while (pending.size > 120) pending.poll()

        if (codec == null && sps != null && pps != null && surface != null) {
            codecHandler.post { maybeStartCodec() }
        }
    }

    private fun extractParams(buf: ByteArray) {
        val starts = findStartCodes(buf)
        for (i in starts.indices) {
            val begin = starts[i]
            val end = if (i + 1 < starts.size) starts[i + 1] else buf.size
            if (begin + 4 >= end) continue
            when (buf[begin + 4].toInt() and 0x1F) {
                7 -> { sps = buf.copyOfRange(begin, end); Log.i(TAG, "SPS ${sps!!.size}B") }
                8 -> { pps = buf.copyOfRange(begin, end); Log.i(TAG, "PPS ${pps!!.size}B") }
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
        try {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(s))
                setByteBuffer("csd-1", ByteBuffer.wrap(p))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.setCallback(callback)
            c.configure(fmt, surf, null, 0)
            c.start()
            codec = c
            Log.i(TAG, "codec started (async)")
        } catch (e: Exception) {
            Log.e(TAG, "codec init failed", e)
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, idx: Int) {
            val entry = pending.poll()
            if (entry == null) {
                codecHandler.postDelayed({
                    val late = pending.poll()
                    if (late != null) {
                        val (nal, pts) = late
                        val buf = c.getInputBuffer(idx) ?: return@postDelayed
                        buf.clear(); buf.put(nal)
                        c.queueInputBuffer(idx, 0, nal.size, pts, 0)
                        fed++
                    } else {
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
            Log.e(TAG, "codec error: ${e.diagnosticInfo}", e)
        }
    }

    companion object { private const val TAG = "VideoDecoder" }
}
