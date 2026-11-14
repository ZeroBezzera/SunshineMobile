package dev.sunshinemobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log

class HostService : Service() {
    private var encoder: ScreenEncoder? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var lastIdrAt = 0L
    private val nsd by lazy { NsdAdvertiser(this) }
    private val http by lazy { GameStreamServer(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY

        startInForeground()

        val resultCode = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { shutdown(); return START_NOT_STICKY }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data)

        val dm = resources.displayMetrics
        val scale = minOf(1.0, 1920.0 / maxOf(dm.widthPixels, dm.heightPixels))
        val w = ((dm.widthPixels * scale).toInt() / 16) * 16
        val h = ((dm.heightPixels * scale).toInt() / 16) * 16

        stats = Stats(w, h)
        encoder = ScreenEncoder(projection, w, h, dm.densityDpi, 60, 20_000_000) { bytes, key, ptsUs ->
            stats.frames++; stats.bytes += bytes.size; if (key) stats.keyFrames++
            http.video.sendFrame(bytes, key, ptsUs)
        }.also { it.start() }
        http.video.onClientReady = { encoder?.requestKeyFrame() }
        http.control.onIdrRequest = {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastIdrAt >= 500) { lastIdrAt = now; encoder?.requestKeyFrame() }
        }

        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "sunshine-lowlat").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (e: Exception) { Log.w(TAG, "wifi lock failed", e) }

        http.start()
        nsd.start(GameStreamServer.HTTP_PORT)
        running = true
        Log.i(TAG, "Host started")
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("host", "Host", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "host")
            .setContentTitle("Sunshine Mobile")
            .setContentText("Screen host running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun shutdown() {
        encoder?.stop(); encoder = null
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
        http.stop(); nsd.stop()
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { if (running) shutdown(); super.onDestroy() }

    class Stats(val width: Int, val height: Int) {
        @Volatile var frames = 0L
        @Volatile var keyFrames = 0L
        @Volatile var bytes = 0L
    }

    companion object {
        const val ACTION_STOP = "dev.sunshinemobile.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val TAG = "HostService"
        @Volatile var running = false
        @Volatile var stats = Stats(0, 0)
    }
}
