package dev.sunshinemobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var button: Button
    private lateinit var pinRow: LinearLayout
    private lateinit var pinInput: EditText
    private val ui = Handler(Looper.getMainLooper())
    private val REQ_CAPTURE = 1

    private val tick = object : Runnable {
        override fun run() {
            val s = HostService.stats
            status.text = if (HostService.running)
                "Running ${s.width}x${s.height}\nframes: ${s.frames}  keyframes: ${s.keyFrames}\n" +
                "encoded: ${s.bytes / 1024} KB\n\nOpen Moonlight on the same Wi-Fi, tap this phone, and pair."
            else "Stopped"
            button.text = if (HostService.running) "Stop host" else "Start host"
            pinRow.visibility = if (PairingState.waiting != null) View.VISIBLE else View.GONE
            ui.postDelayed(this, 500)
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

        pinRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        pinRow.addView(TextView(this).apply {
            text = "Pairing request!\nEnter the PIN shown in Moonlight:"
            textSize = 16f; gravity = Gravity.CENTER
        })
        pinInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "PIN"; gravity = Gravity.CENTER
        }
        pinRow.addView(pinInput)
        pinRow.addView(Button(this).apply {
            text = "Submit PIN"
            setOnClickListener {
                val pin = pinInput.text.toString().trim()
                if (pin.isNotEmpty()) { PairingState.submit(pin); pinInput.setText("") }
            }
        })

        root.addView(status); root.addView(button); root.addView(pinRow)
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
