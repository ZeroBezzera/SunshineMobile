package dev.sunshinemobile

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Captures the screen via MediaProjection and encodes it to H.264 with the
 * hardware MediaCodec. Each encoded access unit goes to [onFrame].
 * This is where the RTP/FEC packetizer will plug in later.
 */
class ScreenEncoder(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onFrame: (data: ByteArray, keyFrame: Boolean, ptsUs: Long) -> Unit
) {
    private var codec: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = c.createInputSurface()
        c.start()
        codec = c

        // Android 14+ requires a callback to be registered before capturing.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stop() }
        }, Handler(Looper.getMainLooper()))

        display = projection.createVirtualDisplay(
            "SunshineMobile", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )

        running = true
        thread = Thread({ drainLoop(c) }, "encoder-drain").also { it.start() }
        Log.i(TAG, "Encoder started ${width}x$height@$fps ${bitrate / 1000}kbps")
    }

    /** Ask the encoder for an IDR frame (needed when a client joins or loses packets). */
    fun requestKeyFrame() {
        val b = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
        try { codec?.setParameters(b) } catch (e: Exception) { Log.w(TAG, "sync frame failed", e) }
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var config: ByteArray? = null // SPS/PPS
        while (running) {
            val idx = try { c.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            if (idx < 0) continue
            val buf = c.getOutputBuffer(idx)
            if (buf != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(bytes)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    config = bytes
                } else {
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    // Prepend SPS/PPS to keyframes so a client can start decoding anywhere.
                    val out = if (key && config != null) config + bytes else bytes
                    onFrame(out, key, info.presentationTimeUs)
                }
            }
            c.releaseOutputBuffer(idx, false)
        }
    }

    fun stop() {
        if (!running && codec == null) return
        running = false
        try { thread?.join(500) } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        display = null; codec = null; thread = null
        Log.i(TAG, "Encoder stopped")
    }

    companion object { private const val TAG = "ScreenEncoder" }
}
