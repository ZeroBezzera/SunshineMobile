package dev.sunshinemobile

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress

/**
 * Owns UDP 47998. Learns the client's address from its ping packets, then sends
 * H.264 frames in Sunshine's NV_VIDEO_PACKET + RTP layout.
 * FEC percentage is 0 for now (no parity packets): fine on a clean LAN.
 */
class VideoSender(private val bindPort: Int) : UdpProbe(bindPort, "video") {
    @Volatile private var active = false
    private var udp: DatagramSocket? = null
    @Volatile private var client: SocketAddress? = null

    /** From the ANNOUNCE SDP (x-nv-video[0].packetSize). Moonlight sent 1392. */
    @Volatile var packetSize = 1392

    /** SPS+PPS in Annex B (with start codes), prepended to key frames that lack them. */
    @Volatile var codecConfig: ByteArray? = null

    /** Called when the client's first ping arrives (a good time to request a key frame). */
    @Volatile var onClientReady: (() -> Unit)? = null

    private var frameIndex = 1
    private var lowSeq = 0
    private var pings = 0
    private var frames = 0
    private var sendErrors = 0

    override fun start() {
        active = true
        Thread({
            try {
                val s = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(bindPort)) }
                udp = s
                SessionLog.add("VIDEO socket bound on $bindPort")
                val buf = ByteArray(2048)
                while (active) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    pings++
                    val from = p.socketAddress
                    if (client != from) {
                        synchronized(this) {
                            client = from
                            frameIndex = 1
                            lowSeq = 0
                        }
                        SessionLog.add("VIDEO client is ${p.address.hostAddress}:${p.port}")
                        onClientReady?.invoke()
                    }
                    if (pings <= 3) {
                        SessionLog.add("UDP video ping #$pings len=${p.length} " +
                            Crypto.hex(buf.copyOf(minOf(p.length, 24))))
                    }
                }
            } catch (e: Exception) {
                if (active) SessionLog.add("VIDEO error: $e")
            }
        }, "video-udp").start()
    }

    override fun stop() {
        active = false
        client = null
        try { udp?.close() } catch (_: Exception) {}
    }

    private fun putLe32(a: ByteArray, o: Int, v: Int) {
        a[o] = v.toByte()
        a[o + 1] = (v shr 8).toByte()
        a[o + 2] = (v shr 16).toByte()
        a[o + 3] = (v shr 24).toByte()
    }

    private fun startsWithSps(f: ByteArray): Boolean {
        if (f.size < 5) return false
        if (f[0].toInt() != 0 || f[1].toInt() != 0) return false
        if (f[2].toInt() == 1) return (f[3].toInt() and 0x1F) == 7
        if (f[2].toInt() == 0 && f[3].toInt() == 1) return (f[4].toInt() and 0x1F) == 7
        return false
    }

    /** Send one encoded frame (Annex B). Safe to call from the encoder thread. */
    @Synchronized
    fun sendFrame(frame: ByteArray, isKey: Boolean, ptsUs: Long) {
        val dst = client ?: return
        val s = udp ?: return

        val cfg = codecConfig
        val prefix = if (isKey && cfg != null && !startsWithSps(frame)) cfg else ByteArray(0)

        val chunk = packetSize - 16          // payload bytes per packet
        val blockSize = packetSize + 16      // full UDP datagram size
        val total = 8 + prefix.size + frame.size
        val n = (total + chunk - 1) / chunk

        // 8-byte frame header + data, zero padded to a whole number of packets
        val body = ByteArray(n * chunk)
        body[0] = 0x01
        body[3] = (if (isKey) 2 else 1).toByte()
        var last = total % chunk
        if (last == 0) last = chunk
        body[4] = (last and 0xFF).toByte()
        body[5] = ((last shr 8) and 0xFF).toByte()
        System.arraycopy(prefix, 0, body, 8, prefix.size)
        System.arraycopy(frame, 0, body, 8 + prefix.size, frame.size)

        val maxShards = 255
        val blocks = (n + maxShards - 1) / maxShards
        if (blocks > 4) {
            SessionLog.add("VIDEO frame too large ($n packets), dropped")
            return
        }
        val perBlock = (n + blocks - 1) / blocks
        val ts = ((ptsUs * 9L) / 100L).toInt()   // 90 kHz RTP clock

        var burst = 0
        try {
            for (b in 0 until blocks) {
                val first = b * perBlock
                val count = minOf(perBlock, n - first)
                if (count <= 0) break
                for (x in 0 until count) {
                    val pkt = ByteArray(blockSize)
                    val seq = (lowSeq + x) and 0xFFFF
                    pkt[0] = 0x90.toByte()                       // RTP v2 + extension flag
                    pkt[2] = (seq shr 8).toByte()
                    pkt[3] = seq.toByte()
                    pkt[4] = (ts shr 24).toByte()
                    pkt[5] = (ts shr 16).toByte()
                    pkt[6] = (ts shr 8).toByte()
                    pkt[7] = ts.toByte()
                    // bytes 12..15 reserved (zero)
                    putLe32(pkt, 16, seq shl 8)                  // streamPacketIndex
                    putLe32(pkt, 20, frameIndex)                 // frameIndex
                    var flags = 0x01                             // contains picture data
                    if (x == 0) flags = flags or 0x04            // start of frame (block)
                    if (x == count - 1) flags = flags or 0x02    // end of frame (block)
                    pkt[24] = flags.toByte()
                    pkt[26] = 0x10                               // multiFecFlags
                    pkt[27] = ((b shl 4) or ((blocks - 1) shl 6)).toByte()
                    putLe32(pkt, 28, (x shl 12) or (count shl 22)) // fecInfo, 0% parity
                    System.arraycopy(body, (first + x) * chunk, pkt, 32, chunk)
                    s.send(DatagramPacket(pkt, pkt.size, dst))
                    if (++burst >= 24) {
                        burst = 0
                        try { Thread.sleep(1) } catch (_: InterruptedException) {}
                    }
                }
                lowSeq = (lowSeq + count) and 0xFFFF
            }
        } catch (e: Exception) {
            if (sendErrors++ < 3) SessionLog.add("VIDEO send error: $e")
        }

        frames++
        if (frames <= 5 || frames % 300 == 0) {
            SessionLog.add("VIDEO frame #$frameIndex key=$isKey bytes=${frame.size} packets=$n")
        }
        frameIndex++
    }
}
