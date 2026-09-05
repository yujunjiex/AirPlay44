package com.localair.airplay.nativebridge

interface VideoSink {
    fun onNalUnit(
        data: ByteArray,
        ptsUs: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
    )
    fun onSessionEnd()
}

interface AudioSink {
    fun onAacFrame(data: ByteArray, ptsUs: Long)
}

object AirPlayNative {
    init { System.loadLibrary("airplay_native") }

    fun start(name: String, mac: ByteArray): Int = nativeStart(name, mac)
    fun stop() = nativeStop()
    fun isRunning(): Boolean = nativeIsRunning()
    fun setVideoSink(sink: VideoSink?) = nativeSetSink(sink)
    fun setAudioSink(sink: AudioSink?) = nativeSetAudioSink(sink)

    var connectionListener: (() -> Unit)? = null

    @JvmStatic fun onConnectionInit() {
        connectionListener?.invoke()
    }

    @JvmStatic private external fun nativeStart(name: String, mac: ByteArray): Int
    @JvmStatic private external fun nativeStop()
    @JvmStatic private external fun nativeIsRunning(): Boolean
    @JvmStatic private external fun nativeSetSink(sink: VideoSink?)
    @JvmStatic private external fun nativeSetAudioSink(sink: AudioSink?)
}
