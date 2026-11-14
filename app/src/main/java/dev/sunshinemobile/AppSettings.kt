package dev.sunshinemobile

import android.content.Context

/* Simple SharedPreferences-backed settings. Input settings apply live; video settings apply on next Start host. */
object AppSettings {
    const val POINTER = "pointer_speed_pct"
    const val SCROLL = "scroll_speed_pct"
    const val SEGMENT = "segment_ms"
    const val EDGE = "edge_dp"
    const val RIGHT_BACK = "right_click_back"
    const val MIDDLE_HOME = "middle_click_home"
    const val BITRATE = "bitrate_mbps"
    const val FPS = "fps"

    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun int(c: Context, k: String, d: Int): Int = p(c).getInt(k, d)
    fun bool(c: Context, k: String, d: Boolean): Boolean = p(c).getBoolean(k, d)
    fun setInt(c: Context, k: String, v: Int) { p(c).edit().putInt(k, v).apply() }
    fun setBool(c: Context, k: String, v: Boolean) { p(c).edit().putBoolean(k, v).apply() }
    fun reset(c: Context) { p(c).edit().clear().apply() }
}
