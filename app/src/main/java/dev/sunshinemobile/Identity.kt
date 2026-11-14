package dev.sunshinemobile

import android.content.Context
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/** The host's long-lived key + self-signed certificate. Created once, then reused. */
class Identity private constructor(val privateKey: PrivateKey, val cert: X509Certificate) {

    /** PEM text bytes, as Moonlight expects in the pairing exchange. */
    val certPem: ByteArray
        get() {
            val b64 = Base64.encodeToString(cert.encoded, Base64.DEFAULT)
            return "-----BEGIN CERTIFICATE-----\n$b64-----END CERTIFICATE-----\n".toByteArray()
        }

    companion object {
        fun parseCert(bytes: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate

        fun load(ctx: Context): Identity {
            val p = ctx.getSharedPreferences("identity", Context.MODE_PRIVATE)
            val k = p.getString("key", null)
            val c = p.getString("cert", null)
            if (k != null && c != null) {
                try {
                    val key = KeyFactory.getInstance("RSA")
                        .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(k, Base64.NO_WRAP)))
                    return Identity(key, parseCert(Base64.decode(c, Base64.NO_WRAP)))
                } catch (_: Exception) { /* regenerate below */ }
            }
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=Sunshine Gamestream Host")
            val from = Date(System.currentTimeMillis() - 24L * 3600 * 1000)
            val to = Date(System.currentTimeMillis() + 20L * 365 * 24 * 3600 * 1000)
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(System.nanoTime()), from, to, name, kp.public)
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            p.edit()
                .putString("key", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
                .putString("cert", Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
                .apply()
            return Identity(kp.private, cert)
        }
    }
}
