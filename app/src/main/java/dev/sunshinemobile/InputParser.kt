package dev.sunshinemobile

/**
 * Decodes the Moonlight input packet carried in control message 0x0206:
 *   [BE32 size (bytes that follow)][LE32 magic][body...]
 */
object InputParser {
    private const val REL = 0x00000007
    private const val ABS = 0x00000005
    private const val BTN_DOWN = 0x00000008
    private const val BTN_UP = 0x00000009
    private const val KEY_DOWN = 0x00000003
    private const val KEY_UP = 0x00000004
    private const val SCROLL = 0x0000000A
    private const val UTF8 = 0x00000017
    private const val HSCROLL = 0x55000001
    private const val TOUCH = 0x55000002

    private var unknownLogs = 0
    private var warned = false

    private fun be16(a: ByteArray, o: Int): Int =
        if (o + 2 > a.size) 0 else ((a[o].toInt() shl 8) or (a[o + 1].toInt() and 0xFF)).toShort().toInt()

    private fun be32(a: ByteArray, o: Int): Int =
        ((a[o].toInt() and 0xFF) shl 24) or ((a[o + 1].toInt() and 0xFF) shl 16) or
            ((a[o + 2].toInt() and 0xFF) shl 8) or (a[o + 3].toInt() and 0xFF)

    private fun le32(a: ByteArray, o: Int): Int =
        (a[o].toInt() and 0xFF) or ((a[o + 1].toInt() and 0xFF) shl 8) or
            ((a[o + 2].toInt() and 0xFF) shl 16) or ((a[o + 3].toInt() and 0xFF) shl 24)

    private fun lef(a: ByteArray, o: Int): Float =
        if (o + 4 > a.size) 0f else java.lang.Float.intBitsToFloat(le32(a, o))

    fun handle(p: ByteArray) {
        if (p.size < 8) return
        val svc = InputAccessibilityService.instance
        if (svc == null) {
            if (!warned) { warned = true; SessionLog.add("INPUT dropped: enable the Accessibility service in the app") }
            return
        }
        val size = be32(p, 0)
        val magic = le32(p, 4)
        val end = minOf(p.size, 4 + size).coerceAtLeast(8)
        val b = p.copyOfRange(8, end)
        when (magic) {
            REL -> svc.moveRel(be16(b, 0), be16(b, 2))
            ABS -> svc.moveAbs(be16(b, 0), be16(b, 2), be16(b, 6), be16(b, 8))
            BTN_DOWN -> if (b.isNotEmpty()) svc.button(b[0].toInt() and 0xFF, true)
            BTN_UP -> if (b.isNotEmpty()) svc.button(b[0].toInt() and 0xFF, false)
            KEY_DOWN, KEY_UP -> if (b.size >= 4) {
                val code = (b[1].toInt() and 0xFF) or ((b[2].toInt() and 0xFF) shl 8)
                svc.key(code and 0xFF, magic == KEY_DOWN, b[3].toInt() and 0xFF)
            }
            SCROLL -> svc.scroll(be16(b, 0), false)
            HSCROLL -> svc.scroll(be16(b, 0), true)
            UTF8 -> svc.text(String(b, Charsets.UTF_8).trimEnd('\u0000'))
            TOUCH -> if (b.size >= 28) {
                // [type u8][pad u8][rotation u16][pointerId u32][x f32][y f32][pressure f32][major f32][minor f32]
                svc.touch(b[0].toInt() and 0xFF, le32(b, 4), lef(b, 8), lef(b, 12))
            }
            else -> if (unknownLogs++ < 10) {
                SessionLog.add("INPUT unknown magic=0x" + Integer.toHexString(magic) + " len=${b.size}")
            }
        }
    }
}
