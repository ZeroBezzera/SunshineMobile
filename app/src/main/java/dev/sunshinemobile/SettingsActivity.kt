package dev.sunshinemobile

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad * 2, pad * 2, pad * 2)
        }

        header(root, "Touch and mouse (apply instantly)")
        slider(root, "Pointer speed", AppSettings.POINTER, 25, 400, 100, "%")
        slider(root, "Scroll speed", AppSettings.SCROLL, 25, 400, 100, "%")
        slider(root, "Touch smoothness: segment length (lower = more real-time, more CPU)", AppSettings.SEGMENT, 1, 40, 10, " ms")
        slider(root, "Screen edge margin (keeps taps away from Home/Back gestures)", AppSettings.EDGE, 0, 60, 28, " dp")
        toggle(root, "Right click = Back", AppSettings.RIGHT_BACK, false)
        toggle(root, "Middle click = Home", AppSettings.MIDDLE_HOME, false)

        header(root, "Video (apply on next Start host)")
        slider(root, "Bitrate", AppSettings.BITRATE, 2, 60, 20, " Mbps")
        slider(root, "Encoder frame rate", AppSettings.FPS, 30, 144, 60, " fps")

        root.addView(Button(this).apply {
            text = "Reset to defaults"
            setOnClickListener { AppSettings.reset(this@SettingsActivity); recreate() }
        })

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun header(root: LinearLayout, text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, pad * 2, 0, pad / 2)
        })
    }

    private fun slider(root: LinearLayout, title: String, key: String, lo: Int, hi: Int, def: Int, unit: String) {
        val cur = AppSettings.int(this, key, def).coerceIn(lo, hi)
        val label = TextView(this).apply {
            text = "$title: $cur$unit"
            textSize = 14f
            setPadding(0, pad, 0, 0)
        }
        val bar = SeekBar(this).apply {
            max = hi - lo
            progress = cur - lo
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + lo
                    label.text = "$title: $v$unit"
                    if (fromUser) AppSettings.setInt(this@SettingsActivity, key, v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(label)
        root.addView(bar)
    }

    private fun toggle(root: LinearLayout, title: String, key: String, def: Boolean) {
        root.addView(Switch(this).apply {
            text = title
            isChecked = AppSettings.bool(this@SettingsActivity, key, def)
            setPadding(0, pad, 0, pad)
            setOnCheckedChangeListener { _, c -> AppSettings.setBool(this@SettingsActivity, key, c) }
        })
    }
}
