package dev.sunshinemobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import org.concentus.OpusApplication
import org.concentus.OpusEncoder
import org.concentus.OpusSignal
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress

/*
 * Owns UDP 48000. Learns the client's address from its ping packets, then captures the
 * phone's playback audio (AudioPlaybackCapture, API 29+), encodes it as stereo Opus and sends
 * it as RTP (payload type 97), one Opus frame per packet. No encryption, no FEC.
 * Apps that forbid audio capture (allowAudioPlaybackCapture=false) are silent.
 */
class AudioSender(private val bindPort: Int, private val ctx: Context) : UdpProbe(bindPort, "audio") {
    @Volatile private var active = false
    private var udp: DatagramSocket? = null
    @Volatile private var client: SocketAddress? = null
    @Volatile var projection: MediaProjection? = null
    @Volatile private var captureRun = false
    @Volatile private var resetSeq = false
    private var capture: Thread? = null
    private var pings = 0

    override fun start() {
        active = true
        Thread({
            try {
                val s = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(bindPort)) }
                udp = s
                SessionLog.add("AUDIO socket bound on $bindPort")
                val buf = ByteArray(2048)
                while (active) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    pings++
                    val from = p.socketAddress
                    if (client != from) {
                        client = from
                        resetSeq = true
                        SessionLog.add("AUDIO client is ${p.address.hostAddress}:${p.port}")
                        ensureCapture()
                    }
                    if (pings <= 3) {
                        SessionLog.add("UDP audio ping #$pings len=${p.length} " +
                            Crypto.hex(buf.copyOf(minOf(p.length, 24))))
                    }
                }
            } catch (e: Exception) {
                if (active) SessionLog.add("AUDIO error: $e")
            }
        }, "audio-udp").start()
    }

    override fun stop() {
        active = false
        captureRun = false
        client = null
        try { udp?.close() } catch (_: Exception) {}
    }

    @Synchronized
    private fun ensureCapture() {
        if (!AppSettings.bool(ctx, AppSettings.AUDIO_ON, true)) {
            SessionLog.add("AUDIO disabled in settings")
            return
        }
        if (capture?.isAlive == true) return
        val mp = projection
        if (mp == null) { SessionLog.add("AUDIO no screen-capture permission yet"); return }
        captureRun = true
        capture = Thread({ captureLoop(mp) }, "audio-capture").also { it.start() }
    }

    /* Opus frame length in ms, as requested by the client in ANNOUNCE (default 5). */
    private fun packetMs(): Int {
        val v = StreamSession.current?.announce?.get("x-nv-aqos.packetDuration")?.trim()?.toIntOrNull() ?: 5
        return if (v == 5 || v == 10 || v == 20) v else 5
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop(mp: MediaProjection) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            SessionLog.add("AUDIO: microphone/record-audio permission not granted (allow it in app settings)")
            return
        }
        val ms = packetMs()
        val frame = 48 * ms              // samples per channel
        val shorts = frame * 2           // interleaved stereo
        var rec: AudioRecord? = null
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf, shorts * 2 * 4))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                SessionLog.add("AUDIO: AudioRecord failed to initialize")
                return
            }

            val enc = OpusEncoder(48000, 2, OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY)
            enc.setBitrate(AppSettings.int(ctx, AppSettings.AUDIO_KBPS, 96) * 1000)
            enc.setSignalType(OpusSignal.OPUS_SIGNAL_MUSIC)
            enc.setComplexity(4)

            rec.startRecording()
            SessionLog.add("AUDIO capture started: ${ms}ms Opus frames, ${AppSettings.int(ctx, AppSettings.AUDIO_KBPS, 96)} kbps")

            val pcm = ShortArray(shorts)
            val opus = ByteArray(1275)
            val pkt = ByteArray(12 + 1275)
            var seq = 0
            var ts = 0
            var sent = 0L
            while (captureRun && active) {
                var got = 0
                while (got < shorts && captureRun) {
                    val n = rec.read(pcm, got, shorts - got)
                    if (n < 0) { SessionLog.add("AUDIO read error $n"); return }
                    got += n
                }
                if (!captureRun) break
                val len = enc.encode(pcm, 0, frame, opus, 0, opus.size)
                if (len <= 0) continue
                val to = client ?: continue
                if (resetSeq) { resetSeq = false; seq = 0; ts = 0 }

                pkt[0] = 0x80.toByte()
                pkt[1] = 97
                pkt[2] = (seq shr 8).toByte(); pkt[3] = seq.toByte()
                pkt[4] = (ts shr 24).toByte(); pkt[5] = (ts shr 16).toByte()
                pkt[6] = (ts shr 8).toByte(); pkt[7] = ts.toByte()
                pkt[8] = 0; pkt[9] = 0; pkt[10] = 0; pkt[11] = 0
                System.arraycopy(opus, 0, pkt, 12, len)
                try {
                    udp?.send(DatagramPacket(pkt, 12 + len, to))
                } catch (e: Exception) {
                    SessionLog.add("AUDIO send error: $e")
                }
                seq = (seq + 1) and 0xFFFF
                ts += ms
                sent++
                if (sent == 1L || sent % 2000 == 0L) SessionLog.add("AUDIO packets sent: $sent (last ${len} bytes)")
            }
        } catch (e: Exception) {
            SessionLog.add("AUDIO capture error: $e")
        } finally {
            try { rec?.stop() } catch (_: Exception) {}
            try { rec?.release() } catch (_: Exception) {}
            SessionLog.add("AUDIO capture stopped")
        }
    }
}
