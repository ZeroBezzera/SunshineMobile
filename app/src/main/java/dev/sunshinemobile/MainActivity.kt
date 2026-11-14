package dev.sunshinemobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var button: Button
    private val ui = Handler(Looper.getMainLooper())
    private val REQ_CAPTURE = 1

    private val tick = object : Runnable {
        override fun run() {
            val s = HostService.stats
            status.text = if (HostService.running)
                "Running ${s.width}x${s.height}\nframes: ${s.frames}  keyframes: ${s.keyFrames}\n" +
                "encoded: ${s.bytes / 1024} KB\n\nOpen Moonlight on the same Wi-Fi and look for this phone."
            else "Stopped"
            button.text = if (HostService.running) "Stop host" else "Start host"
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER }
        button = Button(this)
        root.addView(status); root.addView(button)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO), 0)

        button.setOnClickListener {
            if (HostService.running) {
                startService(Intent(this, HostService::class.java).setAction(HostService.ACTION_STOP))
            } else {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                @Suppress("DEPRECATION")
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, HostService::class.java)
                .putExtra(HostService.EXTRA_CODE, resultCode)
                .putExtra(HostService.EXTRA_DATA, data))
        }
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }
}
