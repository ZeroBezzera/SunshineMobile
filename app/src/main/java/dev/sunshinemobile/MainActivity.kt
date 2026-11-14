package dev.sunshinemobile

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var button: Button
    private lateinit var pinRow: LinearLayout
    private lateinit var pinInput: EditText
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private val ui = Handler(Looper.getMainLooper())
    private val REQ_CAPTURE = 1
    private var shownLogVersion = -1

    private val tick = object : Runnable {
        override fun run() {
            val s = HostService.stats
            status.text = if (HostService.running)
                "Running ${s.width}x${s.height}  frames: ${s.frames}  keyframes: ${s.keyFrames}  ${s.bytes / 1024} KB"
            else "Stopped"
            button.text = if (HostService.running) "Stop host" else "Start host"
            pinRow.visibility = if (PairingState.waiting != null) View.VISIBLE else View.GONE
            if (SessionLog.version != shownLogVersion) {
                shownLogVersion = SessionLog.version
                logView.text = SessionLog.text()
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER }
        button = Button(this)

        pinRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        pinRow.addView(TextView(this).apply {
            text = "Pairing request! Enter the PIN shown in Moonlight:"
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

        val copy = Button(this).apply {
            text = "Copy log"
            setOnClickListener {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("log", SessionLog.text()))
                Toast.makeText(this@MainActivity, "Log copied", Toast.LENGTH_SHORT).show()
            }
        }

        val settingsBtn = Button(this).apply {
            text = "Settings"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }

        val a11y = Button(this).apply {
            text = "Enable remote input (Accessibility)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        logView = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(logView) }

        root.addView(status); root.addView(button); root.addView(pinRow); root.addView(copy); root.addView(a11y); root.addView(settingsBtn)
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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

    override fun onResume() { super.onResume(); shownLogVersion = -1; ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }
}
