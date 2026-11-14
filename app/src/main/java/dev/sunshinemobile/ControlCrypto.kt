package dev.sunshinemobile

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Control stream decryption (Nvidia "old style", control protocol 13 without SS_ENC_CONTROL_V2):
 * AES-128-GCM, key = rikey from /launch, 16-byte IV = [seq & 0xFF, 0, 0, ...].
 * Wire layout of the tagged data is [16-byte tag][ciphertext]; Java wants [ciphertext][tag].
 */
object ControlCrypto {
    @Volatile private var key: SecretKeySpec? = null

    fun hasKey(): Boolean = key != null

    fun setKey(rikey: ByteArray) {
        key = SecretKeySpec(rikey, "AES")
        SessionLog.add("CTRL key set (${rikey.size} bytes)")
    }

    fun setKeyHex(hex: String) {
        val clean = hex.trim()
        if (clean.length % 2 != 0) return
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        setKey(out)
    }

    fun decryptControl(seq: Int, tagged: ByteArray): ByteArray? {
        val k = key ?: return null
        if (tagged.size < 16) return null
        return try {
            val iv = ByteArray(16)
            iv[0] = seq.toByte()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
            val ct = ByteArray(tagged.size)
            System.arraycopy(tagged, 16, ct, 0, tagged.size - 16)
            System.arraycopy(tagged, 0, ct, tagged.size - 16, 16)
            c.doFinal(ct)
        } catch (e: Exception) {
            null
        }
    }
}
