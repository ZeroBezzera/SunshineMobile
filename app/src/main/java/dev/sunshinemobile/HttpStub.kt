package dev.sunshinemobile

import android.content.Context
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Minimal stand-in for the GameStream HTTP endpoint (port 47989).
 * Only answers /serverinfo so Moonlight can list the host as "unpaired".
 * Pairing (/pair), /applist, /launch and the HTTPS port are NOT implemented yet.
 */
class HttpStub(private val ctx: Context, private val port: Int) {
    private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        running = true
        Thread({
            try {
                val s = ServerSocket(port).also { server = it }
                while (running) {
                    val sock = s.accept()
                    Thread { handle(sock) }.start()
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "server error", e)
            }
        }, "http-stub").start()
    }

    private fun handle(sock: Socket) = sock.use {
        val reader = it.getInputStream().bufferedReader()
        val request = reader.readLine() ?: return
        while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
        Log.i(TAG, "HTTP: $request")

        val body = if (request.contains("/serverinfo")) serverInfo() else
            """<?xml version="1.0" encoding="utf-8"?><root status_code="404" status_message="Not implemented"/>"""
        val bytes = body.toByteArray()
        val out = it.getOutputStream()
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun serverInfo(): String {
        val prefs = ctx.getSharedPreferences("host", Context.MODE_PRIVATE)
        val uid = prefs.getString("uid", null) ?: java.util.UUID.randomUUID().toString()
            .replace("-", "").take(16).uppercase().also { prefs.edit().putString("uid", it).apply() }
        return """<?xml version="1.0" encoding="utf-8"?>
<root status_code="200">
<hostname>${Build.MODEL}</hostname>
<appversion>7.1.431.-1</appversion>
<GfeVersion>3.23.0.74</GfeVersion>
<uniqueid>$uid</uniqueid>
<HttpsPort>47984</HttpsPort>
<ExternalPort>47989</ExternalPort>
<mac>00:00:00:00:00:00</mac>
<LocalIP>${localIp()}</LocalIP>
<ServerCodecModeSupport>1</ServerCodecModeSupport>
<PairStatus>0</PairStatus>
<currentgame>0</currentgame>
<state>SUNSHINE_SERVER_FREE</state>
</root>"""
    }

    private fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: "0.0.0.0"
    } catch (_: Exception) { "0.0.0.0" }

    fun stop() { running = false; try { server?.close() } catch (_: Exception) {} }

    companion object { private const val TAG = "HttpStub" }
}
