package dev.sunshinemobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.hypot

/*
 * Injects the remote client's input into this phone without root:
 *  - mouse/touch  -> dispatchGesture (tap, long-press, drag/swipe, wheel = swipe)
 *  - right / middle click -> Back / Home
 *  - keyboard / text -> ACTION_SET_TEXT on the focused field, Esc = Back, Win = Home
 */
class InputAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var sw = 1080
    private var sh = 1920
    private var cx = 540f
    private var cy = 960f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var leftDown = false
    private var logs = 0
    private var touchId = -1

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshSize()
        cx = sw / 2f; cy = sh / 2f
        SessionLog.add("INPUT accessibility service connected ${sw}x${sh}")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    @Suppress("DEPRECATION")
    private fun refreshSize() {
        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        if (dm.widthPixels > 0 && dm.heightPixels > 0) { sw = dm.widthPixels; sh = dm.heightPixels }
    }

    private fun clampCursor() {
        cx = cx.coerceIn(0f, sw - 1f)
        cy = cy.coerceIn(0f, sh - 1f)
    }

    private fun log(s: String) { if (logs++ < 25) SessionLog.add("INPUT $s") }

    // ---- pointer ---------------------------------------------------------

    fun moveRel(dx: Int, dy: Int) = main.post {
        cx += dx; cy += dy; clampCursor()
    }

    fun moveAbs(x: Int, y: Int, refW: Int, refH: Int) = main.post {
        if (refW > 0 && refH > 0) {
            cx = x * sw.toFloat() / refW
            cy = y * sh.toFloat() / refH
            clampCursor()
        }
    }

    fun button(btn: Int, down: Boolean) = main.post {
        when (btn) {
            1 -> if (down) {
                refreshSize()
                leftDown = true; downX = cx; downY = cy; downAt = System.currentTimeMillis()
            } else if (leftDown) {
                leftDown = false
                finishPress()
            }
            2 -> if (!down) { log("middle click -> HOME"); performGlobalAction(GLOBAL_ACTION_HOME) }
            3 -> if (!down) { log("right click -> BACK"); performGlobalAction(GLOBAL_ACTION_BACK) }
        }
    }

    private fun finishPress() {
        val held = System.currentTimeMillis() - downAt
        val slop = 24f * resources.displayMetrics.density
        if (hypot(cx - downX, cy - downY) < slop) {
            val dur = if (held > 450) held.coerceAtMost(1500) else 25L
            log("tap ${downX.toInt()},${downY.toInt()} ${dur}ms")
            stroke(downX, downY, downX, downY, dur)
        } else {
            log("drag ${downX.toInt()},${downY.toInt()} -> ${cx.toInt()},${cy.toInt()}")
            stroke(downX, downY, cx, cy, held.coerceIn(80L, 1500L))
        }
    }

    fun scroll(amount: Int, horizontal: Boolean) = main.post {
        // 120 units per wheel notch; positive = wheel up = content moves down = finger swipes down
        val notches = amount / 120f
        val d = (notches * 0.18f * (if (horizontal) sw else sh)).coerceIn(-0.5f * sh, 0.5f * sh)
        if (d == 0f) return@post
        if (horizontal) stroke(cx, cy, (cx + d).coerceIn(0f, sw - 1f), cy, 140)
        else stroke(cx, cy, cx, (cy + d).coerceIn(0f, sh - 1f), 140)
    }

    /* Native touch events (only sent by Moonlight if the host advertises touch support). */
    fun touch(type: Int, id: Int, nx: Float, ny: Float) = main.post {
        when (type) {
            0x01 -> if (touchId == -1) { // down
                refreshSize()
                touchId = id
                cx = nx * sw; cy = ny * sh; clampCursor()
                leftDown = true; downX = cx; downY = cy; downAt = System.currentTimeMillis()
            }
            0x03 -> if (id == touchId) { cx = nx * sw; cy = ny * sh; clampCursor() }
            0x02 -> if (id == touchId) {
                cx = nx * sw; cy = ny * sh; clampCursor()
                touchId = -1; leftDown = false
                finishPress()
            }
            0x04, 0x07 -> { touchId = -1; leftDown = false }
        }
    }

    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long) {
        val path = Path().apply {
            moveTo(x1.coerceIn(0f, sw - 1f), y1.coerceIn(0f, sh - 1f))
            if (x1 != x2 || y1 != y2) lineTo(x2.coerceIn(0f, sw - 1f), y2.coerceIn(0f, sh - 1f))
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durMs.coerceAtLeast(1)))
            .build()
        dispatchGesture(g, null, null)
    }

    // ---- keyboard --------------------------------------------------------

    fun key(vk: Int, down: Boolean, mods: Int) = main.post {
        if (!down) return@post
        val shift = (mods and 0x01) != 0
        when (vk) {
            0x1B -> performGlobalAction(GLOBAL_ACTION_BACK)            // Esc
            0x5B, 0x5C -> performGlobalAction(GLOBAL_ACTION_HOME)      // Win
            0x08 -> editFocused { if (it.isNotEmpty()) it.dropLast(1) else it }   // Backspace
            0x0D -> enter()
            else -> charFor(vk, shift)?.let { ch -> editFocused { it + ch } }
        }
    }

    fun text(s: String) = main.post { if (s.isNotEmpty()) editFocused { it + s } }

    private fun enter() {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else if (node.isMultiLine) {
            editFocused { it + "\n" }
        }
    }

    private fun editFocused(f: (String) -> String) {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) { log("no focused text field"); return }
        val showingHint = Build.VERSION.SDK_INT >= 26 && node.isShowingHintText
        val cur = if (showingHint) "" else node.text?.toString() ?: ""
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, f(cur))
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun charFor(vk: Int, shift: Boolean): Char? = when (vk) {
        in 0x41..0x5A -> if (shift) vk.toChar() else (vk + 32).toChar()
        in 0x30..0x39 -> if (shift) ")!@#$%^&*(".getOrNull(vk - 0x30) else vk.toChar()
        0x20 -> ' '
        0xBA -> if (shift) ':' else ';'
        0xBB -> if (shift) '+' else '='
        0xBC -> if (shift) '<' else ','
        0xBD -> if (shift) '_' else '-'
        0xBE -> if (shift) '>' else '.'
        0xBF -> if (shift) '?' else '/'
        0xC0 -> if (shift) '~' else '`'
        0xDB -> if (shift) '{' else '['
        0xDC -> if (shift) '|' else '\\'
        0xDD -> if (shift) '}' else ']'
        0xDE -> if (shift) '"' else '\''
        else -> null
    }

    companion object {
        @Volatile var instance: InputAccessibilityService? = null
    }
}
