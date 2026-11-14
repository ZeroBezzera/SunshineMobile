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

/*
 * Injects the remote client's input into this phone without root.
 *  - Touch / left mouse button: a continuous stroke that follows the pointer in real time
 *    (chained short gesture segments), so press, hold and drag happen while you do them.
 *  - Wheel = quick swipe. Right/middle click = Back/Home (optional, see Settings).
 *  - Keyboard / text -> ACTION_SET_TEXT on the focused field.
 */
class InputAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var sw = 1080
    private var sh = 1920
    private var cx = 540f
    private var cy = 960f
    private var logs = 0
    private var touchId = -1

    // live stroke state (main thread only)
    private var cur: GestureDescription.StrokeDescription? = null
    private var strokeActive = false
    private var inFlight = false
    private var pendingUp = false
    private var lastX = 0f
    private var lastY = 0f
    private var gen = 0

    private val idleTick = Runnable { pump(true) }

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

    private fun si(key: String, def: Int) = AppSettings.int(applicationContext, key, def)
    private fun sb(key: String, def: Boolean) = AppSettings.bool(applicationContext, key, def)

    private fun margin(): Float = si(AppSettings.EDGE, 28) * resources.displayMetrics.density
    private fun clampX(v: Float): Float { val m = margin(); return v.coerceIn(m, (sw - m).coerceAtLeast(m)) }
    private fun clampY(v: Float): Float { val m = margin(); return v.coerceIn(m, (sh - m).coerceAtLeast(m)) }

    private fun clampCursor() {
        cx = cx.coerceIn(0f, sw - 1f)
        cy = cy.coerceIn(0f, sh - 1f)
    }

    private fun log(s: String) { if (logs++ < 80) SessionLog.add("INPUT $s") }

    // ---- pointer ---------------------------------------------------------

    fun moveRel(dx: Int, dy: Int) = main.post {
        val k = si(AppSettings.POINTER, 100) / 100f
        cx += dx * k; cy += dy * k; clampCursor()
        onMoved()
    }

    fun moveAbs(x: Int, y: Int, refW: Int, refH: Int) = main.post {
        if (refW > 0 && refH > 0) {
            cx = x * sw.toFloat() / refW
            cy = y * sh.toFloat() / refH
            clampCursor()
            onMoved()
        }
    }

    fun button(btn: Int, down: Boolean) = main.post {
        log("button $btn ${if (down) "down" else "up"} at ${cx.toInt()},${cy.toInt()}")
        when (btn) {
            1 -> if (down) { refreshSize(); beginStroke() } else endStroke()
            2 -> if (!down && sb(AppSettings.MIDDLE_HOME, false)) performGlobalAction(GLOBAL_ACTION_HOME)
            3 -> if (!down && sb(AppSettings.RIGHT_BACK, false)) performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    /* Native touch events (only sent by Moonlight if the host advertises touch support). */
    fun touch(type: Int, id: Int, nx: Float, ny: Float) = main.post {
        when (type) {
            0x01 -> if (touchId == -1) {
                refreshSize()
                touchId = id
                cx = nx * sw; cy = ny * sh; clampCursor()
                beginStroke()
            }
            0x03 -> if (id == touchId) { cx = nx * sw; cy = ny * sh; clampCursor(); onMoved() }
            0x02 -> if (id == touchId) {
                cx = nx * sw; cy = ny * sh; clampCursor()
                touchId = -1
                endStroke()
            }
            0x04, 0x07 -> { touchId = -1; endStroke() }
        }
    }

    private fun onMoved() { if (strokeActive && !inFlight) pump(false) }

    private fun resetStroke() {
        main.removeCallbacks(idleTick)
        cur = null; strokeActive = false; inFlight = false; pendingUp = false
    }

    private fun beginStroke() {
        gen++
        resetStroke()
        val x = clampX(cx); val y = clampY(cy)
        lastX = x; lastY = y
        val path = Path().apply { moveTo(x, y) }
        val s = GestureDescription.StrokeDescription(path, 0, 1, true)
        cur = s; strokeActive = true; pendingUp = false
        send(s, false)
    }

    private fun endStroke() {
        if (!strokeActive) return
        pendingUp = true
        pump(false)
    }

    /* Send the next segment of the live stroke toward the latest pointer position. */
    private fun pump(force: Boolean) {
        val s = cur ?: return
        if (!strokeActive || inFlight) return
        val tx = clampX(cx); val ty = clampY(cy)
        val ending = pendingUp
        if (!ending && !force && tx == lastX && ty == lastY) return
        val path = Path().apply {
            moveTo(lastX, lastY)
            if (tx != lastX || ty != lastY) lineTo(tx, ty)
        }
        val dur = si(AppSettings.SEGMENT, 10).toLong().coerceAtLeast(1)
        val next = try {
            s.continueStroke(path, 0, dur, !ending)
        } catch (e: Exception) {
            log("continueStroke failed: $e"); resetStroke(); return
        }
        cur = next; lastX = tx; lastY = ty
        send(next, ending)
    }

    private fun send(s: GestureDescription.StrokeDescription, last: Boolean) {
        val myGen = gen
        inFlight = true
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(s).build(),
            object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    if (myGen != gen) return
                    inFlight = false
                    if (last) { resetStroke(); return }
                    pump(false)
                    // keep a held finger alive even when the pointer is still
                    if (strokeActive && !inFlight) { main.removeCallbacks(idleTick); main.postDelayed(idleTick, 80) }
                }
                override fun onCancelled(d: GestureDescription?) {
                    if (myGen != gen) return
                    log("gesture cancelled")
                    resetStroke()
                }
            }, main
        )
        if (!ok) { log("dispatch refused"); resetStroke() }
    }

    // ---- wheel -----------------------------------------------------------

    fun scroll(amount: Int, horizontal: Boolean) = main.post {
        // 120 units per wheel notch; positive = wheel up = content moves down = finger swipes down
        val k = si(AppSettings.SCROLL, 100) / 100f
        val notches = amount / 120f
        val d = (notches * 0.18f * k * (if (horizontal) sw else sh)).coerceIn(-0.5f * sh, 0.5f * sh)
        if (d == 0f) return@post
        val x1 = clampX(cx); val y1 = clampY(cy)
        val path = Path().apply {
            moveTo(x1, y1)
            if (horizontal) lineTo(clampX(cx + d), y1) else lineTo(x1, clampY(cy + d))
        }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 140)).build(),
            null, null
        )
    }

    // ---- keyboard --------------------------------------------------------

    fun key(vk: Int, down: Boolean, mods: Int) = main.post {
        log("key vk=0x${vk.toString(16)} down=$down mods=$mods")
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
