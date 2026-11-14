package dev.sunshinemobile

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Control stream decryption. Key = rikey from /launch, AES-128-GCM, 16-byte tag.
 * Wire layout of an encrypted ENet message body:
 *   [LE16 0x0001][LE16 len][LE32 seq][16-byte tag][ciphertext]
 * The exact IV layout differs between Nvidia-style and Sunshine "v2" control encryption,
 * so we try the known variants on the first packets and lock onto the one whose GCM tag verifies.
 */
object ControlCrypto {
    @Volatile private var key: SecretKeySpec? = null
    @Volatile private var variant = -1
    private var failLogs = 0

    fun hasKey(): Boolean = key != null

    fun setKey(rikey: ByteArray) {
        key = SecretKeySpec(rikey, "AES")
        variant = -1
        failLogs = 0
        SessionLog.add("CTRL key set (${rikey.size} bytes)")
    }

    fun setKeyHex(hex: String) {
        val clean = hex.trim()
        if (clean.length % 2 != 0) return
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        setKey(out)
    }

    private fun ivFor(kind: Int, seq: Int): ByteArray {
        val c = 'C'.code.toByte()
        return when (kind) {
            0 -> ByteArray(16).also { it[0] = seq.toByte() }                       // legacy: 16 bytes, low seq byte
            1 -> ByteArray(12).also { le32(it, seq); it[10] = c; it[11] = c }      // v2: LE32 seq, 'C','C'
            2 -> ByteArray(12).also { it[0] = seq.toByte(); it[10] = c; it[11] = c }
            3 -> ByteArray(12).also { it[0] = seq.toByte() }
            else -> ByteArray(12).also { le32(it, seq); it[10] = c }
        }
    }

    private fun le32(a: ByteArray, v: Int) {
        a[0] = v.toByte(); a[1] = (v shr 8).toByte(); a[2] = (v shr 16).toByte(); a[3] = (v shr 24).toByte()
    }

    private fun attempt(v: Int, seq: Int, tagged: ByteArray): ByteArray? {
        val k = key ?: return null
        if (tagged.size < 16) return null
        return try {
            val tagFirst = v < 5
            val n = tagged.size - 16
            val ct = ByteArray(tagged.size)
            if (tagFirst) {
                System.arraycopy(tagged, 16, ct, 0, n)
                System.arraycopy(tagged, 0, ct, n, 16)
            } else {
                System.arraycopy(tagged, 0, ct, 0, tagged.size)
            }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, ivFor(v % 5, seq)))
            c.doFinal(ct)
        } catch (e: Exception) {
            null
        }
    }

    fun decrypt(seq: Int, tagged: ByteArray): ByteArray? {
        if (key == null) return null
        val locked = variant
        if (locked >= 0) return attempt(locked, seq, tagged)
        for (v in 0 until 10) {
            val r = attempt(v, seq, tagged)
            if (r != null) {
                variant = v
                SessionLog.add("CTRL decrypt OK, variant=$v (iv=${v % 5}, tagFirst=${v < 5})")
                return r
            }
        }
        if (failLogs++ < 3) SessionLog.add("CTRL no IV variant matched seq=$seq len=${tagged.size}")
        return null
    }

    fun decryptControl(seq: Int, tagged: ByteArray): ByteArray? = decrypt(seq, tagged)
}
