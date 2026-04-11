package com.localair.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.localair.airplay.nativebridge.VideoSink
import java.nio.ByteBuffer

/**
 * Receives H.264 NAL units from the native AirPlay lib and feeds them to
 * MediaCodec, rendered to the AirPlay SurfaceView. RPiPlay's raop_rtp_mirror
 * hands us an Annex-B buffer that may contain multiple NALs concatenated
 * (typically SPS+PPS+IDR on the first keyframe), so we walk the buffer to
 * extract SPS and PPS before configuring the codec, then feed the whole
 * buffer as-is (MediaCodec tolerates in-band params for AVC Annex-B).
 */
class VideoDecoder(
    private val onFramesChanged: (Boolean) -> Unit = {},
) : VideoSink {

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var receivedAny = false
    private var rendered = 0L

    fun attach(s: Surface) { surface = s }

    fun release() {
        codec?.runCatching { stop(); release() }
        codec = null
        surface = null
        sps = null; pps = null
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
            tryConfigure()
        }
        feed(data, ptsUs)
    }

    /** Walk Annex-B buffer and stash SPS (type 7) and PPS (type 8) standalone. */
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
                out.add(i)
                i += 4
            } else i++
        }
        return out
    }

    private fun dumpNalTypes(buf: ByteArray): String {
        val starts = findStartCodes(buf)
        return starts.joinToString(",") { (buf[it + 4].toInt() and 0x1F).toString() }
    }

    private fun tryConfigure() {
        val s = sps ?: return
        val p = pps ?: return
        if (codec != null) return
        val surf = surface ?: return

        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(s))
            setByteBuffer("csd-1", ByteBuffer.wrap(p))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(fmt, surf, null, 0)
            start()
        }
        Log.i(TAG, "MediaCodec configured and started")
    }

    private fun feed(nal: ByteArray, ptsUs: Long) {
        val c = codec ?: return
        val idx = c.dequeueInputBuffer(10_000)
        if (idx < 0) return
        val buf = c.getInputBuffer(idx) ?: return
        buf.clear(); buf.put(nal)
        c.queueInputBuffer(idx, 0, nal.size, ptsUs, 0)

        val info = MediaCodec.BufferInfo()
        var out = c.dequeueOutputBuffer(info, 0)
        while (out >= 0) {
            c.releaseOutputBuffer(out, true)
            rendered++
            if (rendered == 1L || rendered % 60 == 0L) {
                Log.i(TAG, "rendered $rendered frames")
            }
            out = c.dequeueOutputBuffer(info, 0)
        }
        if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            Log.i(TAG, "output format: ${c.outputFormat}")
        }
    }

    companion object { private const val TAG = "VideoDecoder" }
}
