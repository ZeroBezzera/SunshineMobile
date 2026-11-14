package dev.sunshinemobile

import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** On-screen debug log (also copyable), so we can see what Moonlight asks for. */
object SessionLog {
    private val lines = ArrayDeque<String>()
    @Volatile var version = 0
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized fun add(s: String) {
        lines.addLast("${fmt.format(Date())} $s")
        while (lines.size > 300) lines.removeFirst()
        version++
    }

    @Synchronized fun text(): String = lines.joinToString("\n")
}

/** State negotiated by /launch and the RTSP handshake. */
class LaunchSession(
    val uid: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val rikey: ByteArray,
    val rikeyId: Int,
    val pingPayload: String,
    val connectData: Long
) {
    @Volatile var announce: Map<String, String> = emptyMap()
}

object StreamSession {
    @Volatile var current: LaunchSession? = null
}

/** Minimal RTSP server (port 48010): OPTIONS, DESCRIBE, SETUP, ANNOUNCE, PLAY. Plaintext only. */
class RtspServer {
    private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        running = true
        Thread({
            try {
                val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
                server = s
                SessionLog.add("RTSP listening on $PORT")
                while (running) {
                    val c = s.accept()
                    Thread { handle(c) }.start()
                }
            } catch (e: Exception) {
                if (running) SessionLog.add("RTSP listen error: $e")
            }
        }, "rtsp").start()
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun handle(sock: Socket) {
        try {
            val inp = sock.getInputStream().buffered()
            val out = sock.getOutputStream()
            while (true) {
                val reqLine = readLine(inp) ?: break
                if (reqLine.isEmpty()) continue
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val l = readLine(inp) ?: break
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = inp.read(body, got, len - got)
                    if (n < 0) break
                    got += n
                }
                val parts = reqLine.split(" ")
                val method = parts[0]
                val target = parts.getOrNull(1) ?: ""
                val cseq = headers["cseq"] ?: "0"
                SessionLog.add("RTSP $method $target")
                dispatch(out, method, target, cseq, String(body))
                out.flush()
                break
            }
        } catch (e: Exception) {
            SessionLog.add("RTSP conn: $e")
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun dispatch(out: OutputStream, method: String, target: String, cseq: String, body: String) {
        val session = StreamSession.current
        when (method) {
            "OPTIONS" -> reply(out, 200, "OK", cseq)
            "DESCRIBE" -> {
                val sdp = buildString {
                    append("a=x-ss-general.featureFlags:0\n")
                    append("a=x-ss-general.encryptionSupported:0\n")
                    append("a=x-ss-general.encryptionRequested:0\n")
                    append("a=fmtp:97 surround-params=21101\n")
                }
                reply(out, 200, "OK", cseq, payload = sdp)
            }
            "SETUP" -> {
                if (session == null) { reply(out, 454, "Session Not Found", cseq); return }
                val type = target.substringAfter('=', "").substringBefore('/')
                val port = when (type) { "audio" -> 48000; "video" -> 47998; "control" -> 47999; else -> -1 }
                if (port < 0) { reply(out, 404, "NOT FOUND", cseq); return }
                val extra = mutableListOf(
                    "Session" to "DEADBEEFCAFE;timeout = 90",
                    "Transport" to "server_port=$port"
                )
                if (type == "control") extra += "X-SS-Connect-Data" to session.connectData.toString()
                else extra += "X-SS-Ping-Payload" to session.pingPayload
                SessionLog.add("RTSP SETUP $type -> port $port")
                reply(out, 200, "OK", cseq, extra)
            }
            "ANNOUNCE" -> {
                val args = mutableMapOf<String, String>()
                body.split("\n", "\r").filter { it.isNotEmpty() }.forEach { line ->
                    if (line.startsWith("a=")) {
                        val i = line.indexOf(':')
                        if (i > 2) args[line.substring(2, i)] = line.substring(i + 1).trimEnd()
                    }
                    SessionLog.add("  SDP $line")
                }
                session?.announce = args
                reply(out, 200, "OK", cseq)
            }
            "PLAY" -> {
                SessionLog.add("RTSP PLAY (video not implemented yet)")
                reply(out, 200, "OK", cseq)
            }
            else -> reply(out, 404, "NOT FOUND", cseq)
        }
    }

    private fun reply(
        out: OutputStream, code: Int, msg: String, cseq: String,
        extra: List<Pair<String, String>> = emptyList(), payload: String = ""
    ) {
        val sb = StringBuilder("RTSP/1.0 $code $msg\r\nCSeq: $cseq\r\n")
        extra.forEach { sb.append("${it.first}: ${it.second}\r\n") }
        if (payload.isNotEmpty()) sb.append("Content-length: ${payload.toByteArray().size}\r\n")
        sb.append("\r\n").append(payload)
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    companion object { const val PORT = 48010 }
}

/** Logs the first few UDP packets on a port so we can see what Moonlight sends (stage 2 input). */
class UdpProbe(private val port: Int, private val name: String) {
    @Volatile private var running = false
    private var sock: DatagramSocket? = null

    fun start() {
        running = true
        Thread({
            try {
                val s = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(port)) }
                sock = s
                val buf = ByteArray(2048)
                var n = 0
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    n++
                    if (n <= 8 || n % 200 == 0) {
                        SessionLog.add("UDP $name #$n from ${p.address.hostAddress}:${p.port} len=${p.length} " +
                            Crypto.hex(buf.copyOf(minOf(p.length, 24))))
                    }
                }
            } catch (e: Exception) {
                if (running) SessionLog.add("UDP $name error: $e")
            }
        }, "udp-$name").start()
    }

    fun stop() {
        running = false
        try { sock?.close() } catch (_: Exception) {}
    }
}
