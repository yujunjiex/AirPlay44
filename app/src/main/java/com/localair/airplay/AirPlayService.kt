package com.localair.airplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import com.localair.airplay.nativebridge.AirPlayNative

class AirPlayService : Service() {
    private var multicastLock: WifiManager.MulticastLock? = null
    private var mdns: MdnsAdvertiser? = null
    private val audio = AudioDecoder()
    val video = VideoDecoder { hasFrames ->
        sendBroadcast(Intent(ACTION_FRAMES_CHANGED).putExtra("hasFrames", hasFrames))
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        instance = this
        startInForeground()
        acquireMulticastLock()
        startReceiver()
    }

    override fun onDestroy() {
        instance = null
        mdns?.unregister()
        AirPlayNative.setVideoSink(null)
        AirPlayNative.setAudioSink(null)
        AirPlayNative.stop()
        video.release()
        audio.release()
        try { multicastLock?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    fun attachSurface(surface: Surface) = video.attachSurface(surface)
    fun detachSurface() = video.detachSurface()

    private fun startReceiver() {
        val name = DeviceIdentity.deviceName(this)
        val mac = DeviceIdentity.macAddress(this)
        AirPlayNative.setVideoSink(video)
        AirPlayNative.setAudioSink(audio)
        AirPlayNative.connectionListener = {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            })
        }
        val port = AirPlayNative.start(name, DeviceIdentity.macBytes(this))
        if (port <= 0) {
            Log.e(TAG, "native AirPlay server failed to start")
            return
        }
        mdns = MdnsAdvertiser(this).also { it.register(port, mac, name) }
        Log.i(TAG, "receiver ready: $name ($mac), port $port")
    }

    private fun acquireMulticastLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock(TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    @Suppress("DEPRECATION")
    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "AirPlay 4.4", NotificationManager.IMPORTANCE_LOW)
            )
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText("无线投屏接收器已启动")
            .setContentIntent(open)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
        startForeground(1, notification)
    }

    companion object {
        const val ACTION_FRAMES_CHANGED = "io.github.yujunjiex.airplay44.FRAMES_CHANGED"
        private const val TAG = "AirPlay44-Service"
        private const val CHANNEL = "airplay44"
        @Volatile var instance: AirPlayService? = null
            private set
    }
}
