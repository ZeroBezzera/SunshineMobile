package dev.sunshinemobile

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal ENet 1.3 server for the Moonlight control stream (UDP 47999).
 * One peer at a time. Handles CONNECT -> ACK + VERIFY_CONNECT, ACKs every reliable command,
 * and logs/dispatches the control messages carried inside (type/len little-endian).
 * Plaintext only (encryptionEnabled:0).
 */
class EnetControl(private val bindPort: Int) : UdpProbe(bindPort, "control") {
    @Volatile private var active = false
    private var udp: DatagramSocket? = null

    @Volatile var onIdrRequest: (() -> Unit)? = null
    @Volatile var onInput: ((ByteArray) -> Unit)? = null

    private var connected = false
    private var peerOutId = 0
    private var connectId: ByteArray? = null
    private val lastSeq = IntArray(256)
    private var pingLogs = 0
    private var fragLogs = 0

    override fun start() {
        active = true
        Thread({
            try {
                val s = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(bindPort)) }
                udp = s
                SessionLog.add("ENET control listening on $bindPort")
                val buf = ByteArray(2048)
                while (active) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    try {
                        handle(s, p, buf)
                    } catch (e: Exception) {
                        SessionLog.add("ENET error: $e")
                    }
                }
            } catch (e: Exception) {
                if (active) SessionLog.add("ENET fatal: $e")
            }
        }, "enet-control").start()
    }

    override fun stop() {
        active = false
        try { udp?.close() } catch (_: Exception) {}
    }

    private fun ByteBuffer.b(v: Int): ByteBuffer = put(v.toByte())
    private fun ByteBuffer.s(v: Int): ByteBuffer = putShort(v.toShort())
    private fun ByteBuffer.i(v: Int): ByteBuffer = putInt(v)

    private fun u16(a: ByteArray, o: Int): Int =
        ((a[o].toInt() and 0xFF) shl 8) or (a[o + 1].toInt() and 0xFF)

    private fun i32(a: ByteArray, o: Int): Int =
        ((a[o].toInt() and 0xFF) shl 24) or ((a[o + 1].toInt() and 0xFF) shl 16) or
            ((a[o + 2].toInt() and 0xFF) shl 8) or (a[o + 3].toInt() and 0xFF)

    private fun fresh(ch: Int, seq: Int): Boolean {
        val d = (seq - lastSeq[ch]) and 0xFFFF
        if (d in 1..0x7FFF) {
            lastSeq[ch] = seq
            return true
        }
        return false
    }

    private fun writeAck(out: ByteBuffer, ch: Int, seq: Int, sentTime: Int) {
        out.b(0x01).b(ch).s(seq).s(seq).s(sentTime)
    }

    private fun writeVerify(out: ByteBuffer, buf: ByteArray, pos: Int) {
        val mtu = minOf(maxOf(i32(buf, pos + 8), 576), 1392)
        val win = minOf(maxOf(i32(buf, pos + 12), 4096), 65536)
        out.b(0x83).b(0xFF).s(1)          // VERIFY_CONNECT | ACK flag, channel 0xFF, reliable seq 1
        out.s(0)                          // our peer id (client addresses us as 0)
        out.b(0).b(0)                     // incoming / outgoing session id
        out.i(mtu)
        out.i(win)
        out.put(buf, pos + 16, 4)         // channel count (echo)
        out.i(0).i(0)                     // incoming / outgoing bandwidth
        out.put(buf, pos + 28, 12)        // throttle interval / accel / decel (echo, raw)
        out.put(buf, pos + 40, 4)         // connect id (echo, raw)
    }

    private fun handle(s: DatagramSocket, p: DatagramPacket, buf: ByteArray) {
        val len = p.length
        if (len < 4) return
        val hdr = u16(buf, 0)
        if ((hdr and 0x4000) != 0) {
            SessionLog.add("ENET compressed packet, ignoring")
            return
        }
        val hasTime = (hdr and 0x8000) != 0
        val sentTime = if (hasTime) u16(buf, 2) else 0
        var pos = if (hasTime) 4 else 2

        val out = ByteBuffer.allocate(1400).order(ByteOrder.BIG_ENDIAN)
        out.putInt(0) // header placeholder (peerID/flags + sentTime), filled in below
        var send = false

        while (pos + 4 <= len) {
            val cmdByte = buf[pos].toInt() and 0xFF
            val cmd = cmdByte and 0x0F
            val needAck = (cmdByte and 0x80) != 0
            val ch = buf[pos + 1].toInt() and 0xFF
            val seq = u16(buf, pos + 2)
            var size: Int

            when (cmd) {
                1 -> size = 8
                2 -> {
                    size = 48
                    if (pos + size <= len) {
                        val cid = buf.copyOfRange(pos + 40, pos + 44)
                        if (connectId == null || !cid.contentEquals(connectId!!)) {
                            connectId = cid
                            lastSeq.fill(0)
                            SessionLog.add(
                                "ENET CONNECT from ${p.address.hostAddress}:${p.port} " +
                                    "peer=${u16(buf, pos + 4)} mtu=${i32(buf, pos + 8)} " +
                                    "channels=${i32(buf, pos + 16)} connectId=${Crypto.hex(cid)}"
                            )
                        }
                        peerOutId = u16(buf, pos + 4) and 0x0FFF
                        connected = true
                        writeAck(out, ch, seq, sentTime)
                        writeVerify(out, buf, pos)
                        send = true
                    }
                }
                3 -> size = 44
                4 -> {
                    size = 8
                    SessionLog.add("ENET DISCONNECT")
                }
                5 -> size = 4
                6 -> {
                    val dl = if (pos + 6 <= len) u16(buf, pos + 4) else 0
                    size = 6 + dl
                    if (pos + size <= len && fresh(ch, seq)) {
                        message(ch, buf.copyOfRange(pos + 6, pos + 6 + dl))
                    }
                }
                7, 9 -> {
                    val dl = if (pos + 8 <= len) u16(buf, pos + 6) else 0
                    size = 8 + dl
                    if (pos + size <= len) message(ch, buf.copyOfRange(pos + 8, pos + 8 + dl))
                }
                8, 12 -> {
                    val dl = if (pos + 8 <= len) u16(buf, pos + 6) else 0
                    size = 24 + dl
                    if (fragLogs++ < 3) SessionLog.add("ENET fragment ch=$ch (not handled yet)")
                }
                10 -> size = 12
                11 -> size = 16
                else -> {
                    SessionLog.add("ENET unknown command $cmd")
                    break
                }
            }
            if (pos + size > len) break

            if (needAck && cmd != 2 && connected) {
                writeAck(out, ch, seq, sentTime)
                send = true
            }
            if (cmd == 4) connected = false
            pos += size
        }

        if (send) {
            out.putShort(0, (0x8000 or (peerOutId and 0x0FFF)).toShort())
            out.putShort(2, ((System.nanoTime() / 1_000_000L).toInt() and 0xFFFF).toShort())
            s.send(DatagramPacket(out.array(), 0, out.position(), p.socketAddress))
        }
    }

    private fun message(ch: Int, d: ByteArray) {
        if (d.size < 4) {
            SessionLog.add("CTRL ch=$ch short ${Crypto.hex(d)}")
            return
        }
        val type = (d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8)
        val plen = (d[2].toInt() and 0xFF) or ((d[3].toInt() and 0xFF) shl 8)
        val payload = d.copyOfRange(4, d.size)
        val hexp = Crypto.hex(payload.copyOf(minOf(payload.size, 32)))
        val t = "0x" + type.toString(16).padStart(4, '0')
        when (type) {
            0x0200 -> if (pingLogs++ < 2) SessionLog.add("CTRL ping ch=$ch")
            0x0302 -> {
                SessionLog.add("CTRL IDR request")
                onIdrRequest?.invoke()
            }
            0x0206 -> onInput?.invoke(payload)
            else -> SessionLog.add("CTRL ch=$ch type=$t len=$plen payload=$hexp")
        }
    }
}
