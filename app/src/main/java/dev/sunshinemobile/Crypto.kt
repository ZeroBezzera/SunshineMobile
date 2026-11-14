package dev.sunshinemobile

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun aes(encrypt: Boolean, key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }

    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        return ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    fun signData(key: PrivateKey, data: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withRSA")
        s.initSign(key)
        s.update(data)
        return s.sign()
    }

    fun verifyData(cert: X509Certificate, data: ByteArray, sig: ByteArray): Boolean {
        val s = Signature.getInstance("SHA256withRSA")
        s.initVerify(cert)
        s.update(data)
        return s.verify(sig)
    }
}
