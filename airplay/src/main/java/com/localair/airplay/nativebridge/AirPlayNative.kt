package com.localair.airplay.nativebridge

interface VideoSink {
    fun onNalUnit(data: ByteArray, ptsUs: Long)
}

object AirPlayNative {
    init { System.loadLibrary("airplay_native") }

    fun start(): Int = nativeStart()
    fun stop() = nativeStop()
    fun setVideoSink(sink: VideoSink?) = nativeSetSink(sink)

    @JvmStatic private external fun nativeStart(): Int
    @JvmStatic private external fun nativeStop()
    @JvmStatic private external fun nativeSetSink(sink: VideoSink?)
}
