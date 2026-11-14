package dev.sunshinemobile

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/* Advertises the host over mDNS as _nvstream._tcp so Moonlight can discover it. */
class NsdAdvertiser(private val ctx: Context) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.RegistrationListener? = null

    fun start(port: Int) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = wifi.createMulticastLock("sunshine-mobile").apply { setReferenceCounted(false); acquire() }

        val info = NsdServiceInfo().apply {
            serviceName = Build.MODEL ?: "Android"
            serviceType = "_nvstream._tcp."
            setPort(port)
        }
        listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "mDNS registered as ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.e(TAG, "mDNS failed: $code") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        try { listener?.let { nsd.unregisterService(it) } } catch (_: Exception) {}
        try { lock?.release() } catch (_: Exception) {}
        listener = null; lock = null
    }

    companion object { private const val TAG = "NsdAdvertiser" }
}
