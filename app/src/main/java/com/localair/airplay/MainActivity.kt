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
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var waiting: TextView
    private val handler = Handler()
    private var surfaceReady = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AirPlayService.ACTION_FRAMES_CHANGED -> {
                    waiting.visibility = if (
                        intent.getBooleanExtra(AirPlayService.EXTRA_HAS_FRAMES, false)
                    ) View.GONE else View.VISIBLE
                }
                AirPlayService.ACTION_VIDEO_SIZE_CHANGED -> updateSurfaceLayout(
                    PixelSize(
                        intent.getIntExtra(AirPlayService.EXTRA_VIDEO_WIDTH, 0),
                        intent.getIntExtra(AirPlayService.EXTRA_VIDEO_HEIGHT, 0),
                    )
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
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
        registerReceiver(
            stateReceiver,
            IntentFilter().apply {
                addAction(AirPlayService.ACTION_FRAMES_CHANGED)
                addAction(AirPlayService.ACTION_VIDEO_SIZE_CHANGED)
            },
        )
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
            service.video.displaySize?.let(::updateSurfaceLayout)
        } else if (attempt < 20) {
            handler.postDelayed({ attachWhenReady(attempt + 1) }, 250)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        attachWhenReady(0)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        AirPlayService.instance?.video?.displaySize?.let(::updateSurfaceLayout)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        AirPlayService.instance?.detachSurface()
        waiting.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(stateReceiver)
        super.onDestroy()
    }

    private fun updateSurfaceLayout(content: PixelSize) {
        if (!content.isValid) return
        root.post {
            val fitted = VideoGeometry.fitInside(PixelSize(root.width, root.height), content)
            if (!fitted.isValid) return@post
            val params = surfaceView.layoutParams as FrameLayout.LayoutParams
            if (params.width == fitted.width && params.height == fitted.height) return@post
            params.width = fitted.width
            params.height = fitted.height
            params.gravity = Gravity.CENTER
            surfaceView.layoutParams = params
            android.util.Log.i("AirPlay44-Activity", "surface ${fitted.width}x${fitted.height} for $content")
        }
    }
}
