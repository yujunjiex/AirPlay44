package com.localair.airplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.localair.airplay.nativebridge.AirPlayNative

class AirPlayService : Service() {

    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var mdns: MdnsAdvertiser
    private val audio = AudioDecoder()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        acquireMulticastLock()
        val port = AirPlayNative.start()
        AirPlayNative.setAudioSink(audio)
        mdns = MdnsAdvertiser(this)
        if (port > 0) {
            mdns.register(port)
            Log.i(TAG, "AirPlay receiver listening on $port")
        } else {
            Log.w(TAG, "native start returned 0 (stub build) — skipping mDNS")
        }
    }

    override fun onDestroy() {
        if (::mdns.isInitialized) mdns.unregister()
        AirPlayNative.setAudioSink(null)
        AirPlayNative.stop()
        audio.release()
        multicastLock?.release()
        super.onDestroy()
    }

    private fun acquireMulticastLock() {
        val wifi = getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock(TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "AirPlay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("localair")
            .setContentText("AirPlay receiver running")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
        startForeground(1, n)
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL = "airplay"
    }
}
