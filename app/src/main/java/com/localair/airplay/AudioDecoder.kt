package com.localair.airplay

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.localair.airplay.nativebridge.AudioSink
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/** AAC-ELD to PCM using APIs available in Android 4.4. */
class AudioDecoder : AudioSink {
    private data class Frame(val bytes: ByteArray, val ptsUs: Long)
    private val pending = ConcurrentLinkedQueue<Frame>()
    @Volatile private var running = true
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private val worker = Thread({ decodeLoop() }, "AirPlay44-Audio").apply { start() }

    override fun onAacFrame(data: ByteArray, ptsUs: Long) {
        pending.offer(Frame(data, ptsUs))
        while (pending.size > 200) pending.poll()
    }

    fun release() {
        running = false
        worker.interrupt()
        try { worker.join(1000) } catch (_: InterruptedException) {}
        releaseDecoder()
        pending.clear()
    }

    private fun decodeLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                if (pending.isEmpty()) {
                    Thread.sleep(5)
                    continue
                }
                if (codec == null) startDecoder()
                val decoder = codec ?: continue
                val inputIndex = decoder.dequeueInputBuffer(5_000)
                if (inputIndex >= 0) {
                    val frame = pending.poll()
                    if (frame == null) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    } else {
                        val input = decoder.inputBuffers[inputIndex]
                        input.clear()
                        input.put(frame.bytes)
                        decoder.queueInputBuffer(inputIndex, 0, frame.bytes.size, frame.ptsUs, 0)
                    }
                }
                var outputIndex = decoder.dequeueOutputBuffer(info, 0)
                while (outputIndex >= 0) {
                    val output = decoder.outputBuffers[outputIndex]
                    if (info.size > 0) {
                        val pcm = ByteArray(info.size)
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        output.get(pcm)
                        track?.write(pcm, 0, pcm.size)
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    outputIndex = decoder.dequeueOutputBuffer(info, 0)
                }
            } catch (interrupted: InterruptedException) {
                // release() wakes the worker.
            } catch (error: Throwable) {
                // Some Android 4.4 TVs do not expose an AAC-ELD decoder. Keep
                // video alive and retry audio on a later session.
                Log.e(TAG, "AAC-ELD decoder unavailable", error)
                releaseDecoder()
                pending.clear()
                try { Thread.sleep(1000) } catch (_: InterruptedException) {}
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startDecoder() {
        val format = MediaFormat.createAudioFormat(AAC_MIME, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_OBJECT_ELD)
            setByteBuffer("csd-0", ByteBuffer.wrap(AAC_ELD_CONFIG))
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
        }
        codec = MediaCodec.createDecoderByType(AAC_MIME).apply {
            configure(format, null, null, 0)
            start()
        }
        val minimum = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        track = AudioTrack(
            AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT, minimum * 2, AudioTrack.MODE_STREAM
        ).apply { play() }
        Log.i(TAG, "AAC-ELD decoder started")
    }

    private fun releaseDecoder() {
        val currentCodec = codec
        codec = null
        try { currentCodec?.stop() } catch (_: Throwable) {}
        try { currentCodec?.release() } catch (_: Throwable) {}
        val currentTrack = track
        track = null
        try { currentTrack?.stop() } catch (_: Throwable) {}
        try { currentTrack?.release() } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "AirPlay44-Audio"
        private const val SAMPLE_RATE = 44100
        private const val AAC_MIME = "audio/mp4a-latm"
        private const val AAC_OBJECT_ELD = 39
        private val AAC_ELD_CONFIG = byteArrayOf(
            0xF8.toByte(), 0xE8.toByte(), 0x50.toByte(), 0x00.toByte()
        )
    }
}
