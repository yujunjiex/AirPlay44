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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import com.localair.airplay.nativebridge.AirPlayNative

class AirPlayService : Service() {
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mdns: MdnsAdvertiser? = null
    private var receiverRestarting = false
    private val handler = Handler(Looper.getMainLooper())
    private val receiverWatchdog = object : Runnable {
        override fun run() {
            if (!AirPlayNative.isRunning()) {
                Log.w(TAG, "AirPlay server stopped unexpectedly; restarting")
                restartReceiver("server health check")
            }
            handler.postDelayed(this, RECEIVER_HEALTH_INTERVAL_MS)
        }
    }
    private val audio = AudioDecoder()
    internal val video = VideoDecoder(
        onFramesChanged = { hasFrames ->
            sendBroadcast(Intent(ACTION_FRAMES_CHANGED).putExtra(EXTRA_HAS_FRAMES, hasFrames))
        },
        onVideoSizeChanged = { size ->
            sendBroadcast(
                Intent(ACTION_VIDEO_SIZE_CHANGED)
                    .putExtra(EXTRA_VIDEO_WIDTH, size.width)
                    .putExtra(EXTRA_VIDEO_HEIGHT, size.height)
            )
        },
        onSessionExpired = {
            handler.post { restartReceiver("completed session cleanup") }
        },
    )

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        instance = this
        startInForeground()
        acquireNetworkLocks()
        startReceiver()
        handler.postDelayed(receiverWatchdog, RECEIVER_HEALTH_INTERVAL_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        instance = null
        AirPlayNative.connectionListener = null
        stopReceiver()
        AirPlayNative.setVideoSink(null)
        AirPlayNative.setAudioSink(null)
        video.release()
        audio.release()
        try { multicastLock?.release() } catch (_: Throwable) {}
        try { wifiLock?.release() } catch (_: Throwable) {}
        try { wakeLock?.release() } catch (_: Throwable) {}
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

    private fun stopReceiver() {
        try { mdns?.unregister() } catch (error: Throwable) {
            Log.w(TAG, "mDNS unregister failed", error)
        }
        mdns = null
        AirPlayNative.stop()
    }

    fun restartReceiver(reason: String = "manual request") {
        if (receiverRestarting) return
        receiverRestarting = true
        Log.i(TAG, "restarting AirPlay receiver: $reason")
        try {
            stopReceiver()
            // nativeStop may emit a final connection-close callback. Clear it
            // after stop so the fresh receiver does not inherit stale media.
            video.resetForReceiverRestart()
            audio.reset()
            startReceiver()
        } finally {
            receiverRestarting = false
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireNetworkLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock(TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (error: Throwable) {
            Log.w(TAG, "high-performance Wi-Fi lock unavailable", error)
        }
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:cpu").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (error: Throwable) {
            Log.w(TAG, "CPU wake lock unavailable", error)
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
        const val ACTION_VIDEO_SIZE_CHANGED = "io.github.yujunjiex.airplay44.VIDEO_SIZE_CHANGED"
        const val EXTRA_HAS_FRAMES = "hasFrames"
        const val EXTRA_VIDEO_WIDTH = "videoWidth"
        const val EXTRA_VIDEO_HEIGHT = "videoHeight"
        private const val TAG = "AirPlay44-Service"
        private const val CHANNEL = "airplay44"
        private const val RECEIVER_HEALTH_INTERVAL_MS = 5_000L
        @Volatile var instance: AirPlayService? = null
            private set
    }
}
