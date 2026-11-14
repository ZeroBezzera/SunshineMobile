package dev.sunshinemobile

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** The PIN the user types into the app; the pairing request waits on this. */
object PairingState {
    @Volatile var waiting: CompletableFuture<String>? = null
    fun submit(pin: String) { waiting?.complete(pin) }
}

/**
 * GameStream-compatible control server:
 *  - HTTP  47989: /serverinfo, /pair (steps 1-4), /unpair
 *  - HTTPS 47984: /serverinfo, /pair (final check), /applist (mutual TLS with the paired client cert)
 * Streaming itself (/launch, RTSP, RTP) is NOT implemented yet.
 */
class GameStreamServer(private val ctx: Context) {
    private val id = Identity.load(ctx)
    private val prefs = ctx.getSharedPreferences("host", Context.MODE_PRIVATE)
    private val pool = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap<String, PairSession>()
    @Volatile private var running = false
    private val sockets = mutableListOf<ServerSocket>()
    private val rtsp = RtspServer()
    val video = VideoSender(47998)
    val control = EnetControl(47999).also { it.onInput = { p -> InputParser.handle(p) } }
    private val probes = listOf(video, control, UdpProbe(48000, "audio"))

    private class PairSession(val salt: ByteArray, val clientCert: X509Certificate) {
        var aesKey = ByteArray(0)
        var serverSecret = ByteArray(0)
        var serverChallenge = ByteArray(0)
        var clientHash = ByteArray(0)
    }

    fun start() {
        running = true
        listen(HTTP_PORT, false)
        listen(HTTPS_PORT, true)
        rtsp.start()
        probes.forEach { it.start() }
    }

    fun stop() {
        running = false
        rtsp.stop()
        probes.forEach { it.stop() }
        StreamSession.current = null
        synchronized(sockets) { sockets.forEach { try { it.close() } catch (_: Exception) {} }; sockets.clear() }
    }

    private fun listen(port: Int, tls: Boolean) {
        pool.execute {
            try {
                val s: ServerSocket = if (tls) {
                    (tlsContext().serverSocketFactory.createServerSocket() as SSLServerSocket).also { it.wantClientAuth = true }
                } else ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                synchronized(sockets) { sockets.add(s) }
                Log.i(TAG, "Listening on $port (tls=$tls)")
                while (running) {
                    val c = s.accept()
                    pool.execute { handle(c, tls) }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "listen $port failed", e)
            }
        }
    }

    private fun tlsContext(): SSLContext {
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("host", id.privateKey, charArrayOf(), arrayOf(id.cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, charArrayOf())
        // Accept any client cert at the TLS layer; we check it against the paired list per request.
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(trustAll), null) }
    }

    private fun handle(sock: Socket, tls: Boolean) {
        sock.use {
            try {
                var peer: X509Certificate? = null
                if (tls) {
                    val ss = sock as SSLSocket
                    ss.startHandshake()
                    peer = try { ss.session.peerCertificates.firstOrNull() as? X509Certificate } catch (_: Exception) { null }
                }
                val reader = sock.getInputStream().bufferedReader()
                val line = reader.readLine()
                if (line != null) {
                    while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                    val target = line.split(" ").getOrNull(1) ?: "/"
                    val path = target.substringBefore('?')
                    val q = parseQuery(target.substringAfter('?', ""))
                    Log.i(TAG, "${if (tls) "HTTPS" else "HTTP"} $path ${q.keys}")
                    if (path != "/serverinfo") SessionLog.add("${if (tls) "HTTPS" else "HTTP"} $path")
                    val body = route(path, q, tls, peer, hostFor(sock)).toByteArray()
                    val out = sock.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                    out.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "request failed: $e")
            }
            Unit
        }
    }

    private fun parseQuery(s: String): Map<String, String> =
        s.split("&").filter { it.isNotEmpty() }.associate {
            val i = it.indexOf('=')
            if (i < 0) it to "" else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }

    private fun xml(inner: String, code: Int = 200, msg: String = ""): String =
        """<?xml version="1.0" encoding="utf-8"?><root status_code="$code" status_message="$msg">$inner</root>"""

    private fun route(path: String, q: Map<String, String>, tls: Boolean, peer: X509Certificate?, host: String): String {
        val uid = q["uniqueid"] ?: ""
        return when (path) {
            "/serverinfo" -> serverInfo(if (tls) (peer != null && isPaired(peer)) else isPairedUid(uid))
            "/pair" -> try { pair(uid, q, tls, peer) } catch (e: Exception) {
                Log.w(TAG, "pair error: $e"); sessions.remove(uid); xml("<paired>0</paired>")
            }
            "/unpair" -> { prefs.edit().remove("paired:$uid").apply(); xml("") }
            "/applist" ->
                if (tls && peer != null && isPaired(peer))
                    xml("<App><IsHdrSupported>0</IsHdrSupported><AppTitle>Phone Screen</AppTitle><ID>1</ID></App>")
                else xml("", 401, "Not paired")
            "/launch", "/resume" ->
                if (tls && peer != null && isPaired(peer)) launch(uid, q, host, path == "/resume")
                else xml("", 401, "Not paired")
            "/cancel" -> { StreamSession.current = null; SessionLog.add("Session cancelled"); xml("<cancel>1</cancel>") }
            else -> xml("", 404, "Not found")
        }
    }

    private fun hostFor(sock: Socket): String {
        val a = sock.localAddress
        return if (a is Inet4Address) a.hostAddress ?: localIp() else localIp()
    }

    /** /launch and /resume: remember the session keys, point Moonlight at our RTSP port. */
    private fun launch(uid: String, q: Map<String, String>, host: String, resume: Boolean): String {
        SessionLog.add((if (resume) "RESUME " else "LAUNCH ") + q.filterKeys { it != "rikey" })
        val rikey = q["rikey"]
        val rikeyId = q["rikeyid"]?.toIntOrNull()
        if (rikey == null || rikeyId == null) return xml("", 400, "Missing a required launch parameter")
        val mode = (q["mode"] ?: "0x0x0").split("x")
        StreamSession.current = LaunchSession(
            uid = uid,
            width = mode.getOrNull(0)?.toIntOrNull() ?: 0,
            height = mode.getOrNull(1)?.toIntOrNull() ?: 0,
            fps = mode.getOrNull(2)?.toIntOrNull() ?: 0,
            rikey = Crypto.unhex(rikey),
            rikeyId = rikeyId,
            pingPayload = Crypto.hex(Crypto.random(8)),
            connectData = java.nio.ByteBuffer.wrap(Crypto.random(4)).int.toLong() and 0xFFFFFFFFL
        )
        // The /launch rikey is also the AES key for the legacy control-stream crypto.
        StreamSession.current?.let { ControlCrypto.setKey(it.rikey) }
        val url = "<sessionUrl0>rtsp://$host:${RtspServer.PORT}</sessionUrl0>"
        return xml(if (resume) "$url<resume>1</resume>" else "$url<gamesession>1</gamesession>")
    }

    // ---- pairing -----------------------------------------------------------

    private fun pair(uid: String, q: Map<String, String>, tls: Boolean, peer: X509Certificate?): String = when {
        q["phrase"] == "getservercert" -> pairStep1(uid, q)
        q["phrase"] == "pairchallenge" ->
            xml(if (tls && peer != null && isPaired(peer)) "<paired>1</paired>" else "<paired>0</paired>")
        q.containsKey("clientchallenge") -> pairStep2(uid, q.getValue("clientchallenge"))
        q.containsKey("serverchallengeresp") -> pairStep3(uid, q.getValue("serverchallengeresp"))
        q.containsKey("clientpairingsecret") -> pairStep4(uid, q.getValue("clientpairingsecret"))
        else -> xml("<paired>0</paired>")
    }

    /** Client sends its cert + salt. We wait for the user to type the PIN shown in Moonlight. */
    private fun pairStep1(uid: String, q: Map<String, String>): String {
        val salt = Crypto.unhex(q["salt"] ?: return xml("<paired>0</paired>"))
        val clientCert = Identity.parseCert(Crypto.unhex(q["clientcert"] ?: return xml("<paired>0</paired>")))
        val session = PairSession(salt, clientCert)
        sessions[uid] = session

        PairingState.waiting?.cancel(true)
        val f = CompletableFuture<String>()
        PairingState.waiting = f
        val pin = try {
            f.get(180, TimeUnit.SECONDS)
        } catch (e: Exception) {
            sessions.remove(uid)
            return xml("<paired>0</paired>")
        } finally {
            if (PairingState.waiting === f) PairingState.waiting = null
        }
        session.aesKey = Crypto.sha256(salt + pin.toByteArray()).copyOf(16)
        return xml("<paired>1</paired><plaincert>${Crypto.hex(id.certPem)}</plaincert>")
    }

    private fun pairStep2(uid: String, hex: String): String {
        val s = sessions[uid] ?: return xml("<paired>0</paired>")
        val clientChallenge = Crypto.aes(false, s.aesKey, Crypto.unhex(hex))
        s.serverSecret = Crypto.random(16)
        s.serverChallenge = Crypto.random(16)
        val hash = Crypto.sha256(clientChallenge + id.cert.signature + s.serverSecret)
        val resp = Crypto.aes(true, s.aesKey, hash + s.serverChallenge)
        return xml("<paired>1</paired><challengeresponse>${Crypto.hex(resp)}</challengeresponse>")
    }

    private fun pairStep3(uid: String, hex: String): String {
        val s = sessions[uid] ?: return xml("<paired>0</paired>")
        s.clientHash = Crypto.aes(false, s.aesKey, Crypto.unhex(hex))
        val sig = Crypto.signData(id.privateKey, s.serverSecret)
        return xml("<paired>1</paired><pairingsecret>${Crypto.hex(s.serverSecret + sig)}</pairingsecret>")
    }

    private fun pairStep4(uid: String, hex: String): String {
        val s = sessions.remove(uid) ?: return xml("<paired>0</paired>")
        val data = Crypto.unhex(hex)
        val secret = data.copyOfRange(0, 16)
        val sig = data.copyOfRange(16, data.size)
        val expected = Crypto.sha256(s.serverChallenge + s.clientCert.signature + secret)
        val ok = expected.contentEquals(s.clientHash) && Crypto.verifyData(s.clientCert, secret, sig)
        if (!ok) return xml("<paired>0</paired>") // wrong PIN or bad signature
        prefs.edit().putString("paired:$uid", Base64.encodeToString(s.clientCert.encoded, Base64.NO_WRAP)).apply()
        Log.i(TAG, "Paired with $uid")
        return xml("<paired>1</paired>")
    }

    private fun isPairedUid(uid: String) = uid.isNotEmpty() && prefs.contains("paired:$uid")

    private fun isPaired(cert: X509Certificate): Boolean =
        prefs.all.any { (k, v) ->
            k.startsWith("paired:") && v is String &&
                try { Base64.decode(v, Base64.NO_WRAP).contentEquals(cert.encoded) } catch (_: Exception) { false }
        }

    // ---- serverinfo --------------------------------------------------------

    private fun serverInfo(paired: Boolean): String {
        val uid = prefs.getString("uid", null) ?: java.util.UUID.randomUUID().toString()
            .replace("-", "").take(16).uppercase().also { prefs.edit().putString("uid", it).apply() }
        return xml(
            "<hostname>${Build.MODEL}</hostname><appversion>7.1.431.-1</appversion>" +
            "<GfeVersion>3.23.0.74</GfeVersion><uniqueid>$uid</uniqueid>" +
            "<HttpsPort>$HTTPS_PORT</HttpsPort><ExternalPort>$HTTP_PORT</ExternalPort>" +
            "<mac>00:00:00:00:00:00</mac><LocalIP>${localIp()}</LocalIP>" +
            "<ServerCodecModeSupport>1</ServerCodecModeSupport>" +
            "<PairStatus>${if (paired) 1 else 0}</PairStatus><currentgame>${if (StreamSession.current != null) 1 else 0}</currentgame>" +
            "<state>${if (StreamSession.current != null) "SUNSHINE_SERVER_BUSY" else "SUNSHINE_SERVER_FREE"}</state>"
        )
    }

    private fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: "0.0.0.0"
    } catch (_: Exception) { "0.0.0.0" }

    companion object {
        const val HTTP_PORT = 47989
        const val HTTPS_PORT = 47984
        private const val TAG = "GameStreamServer"
    }
}
