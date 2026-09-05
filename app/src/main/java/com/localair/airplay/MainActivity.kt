package com.localair.airplay

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var waiting: TextView
    private val handler = Handler()
    private var surfaceReady = false

    private val framesReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            waiting.visibility = if (intent.getBooleanExtra("hasFrames", false)) View.GONE else View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this).apply { holder.addCallback(this@MainActivity) }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        waiting = TextView(this).apply {
            text = "${DeviceIdentity.deviceName(this@MainActivity)}\n${getString(R.string.waiting)}"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            textSize = 24f
            gravity = Gravity.CENTER
        }
        root.addView(waiting, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        startService(Intent(this, AirPlayService::class.java))
        registerReceiver(framesReceiver, IntentFilter(AirPlayService.ACTION_FRAMES_CHANGED))
    }

    override fun onResume() {
        super.onResume()
        attachWhenReady(0)
    }

    private fun attachWhenReady(attempt: Int) {
        if (!surfaceReady) return
        val service = AirPlayService.instance
        if (service != null) {
            service.attachSurface(surfaceView.holder.surface)
        } else if (attempt < 20) {
            handler.postDelayed({ attachWhenReady(attempt + 1) }, 250)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        attachWhenReady(0)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        AirPlayService.instance?.detachSurface()
        waiting.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(framesReceiver)
        super.onDestroy()
    }
}
