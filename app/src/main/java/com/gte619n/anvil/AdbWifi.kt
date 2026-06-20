package com.gte619n.anvil

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

/**
 * Discovers this device's wireless-debugging endpoints via mDNS — the "connect" service
 * (`_adb-tls-connect._tcp`, always advertised while Wireless debugging is on) and the "pairing"
 * service (`_adb-tls-pairing._tcp`, advertised only while the "Pair device with pairing code"
 * dialog is open). Both ports change each session — this grabs the current one.
 */
class AdbWifi(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private var listener: NsdManager.DiscoveryListener? = null
    private var onResult: ((String, Int) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var what = "service"
    private val timeout = Runnable {
        fail("No $what service found. Make sure Wireless debugging (and the pairing dialog, when pairing) is open on the phone, then retry.")
    }

    fun discover(serviceType: String, onResult: (host: String, port: Int) -> Unit, onError: (String) -> Unit) {
        stop()
        this.onResult = onResult
        this.onError = onError
        this.what = if (serviceType == PAIRING) "pairing" else "wireless-debugging"
        main.postDelayed(timeout, 8_000)
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) = fail("Couldn't start discovery ($e)")
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                try {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(s, resolveListener())
                } catch (_: Exception) {
                }
            }
        }
        listener = l
        try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            fail("Couldn't start discovery: ${e.message}")
        }
    }

    private fun resolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(si: NsdServiceInfo, e: Int) {} // keep waiting for another service
        override fun onServiceResolved(si: NsdServiceInfo) {
            val host = ipv4(si) ?: return // adb wants the IPv4 LAN address; ignore IPv6-only results
            succeed(host, si.port)
        }
    }

    /** Prefer the IPv4 LAN address (adb connect/pair needs it, not a link-local IPv6). */
    private fun ipv4(si: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= 34) {
            si.hostAddresses.firstOrNull { it is Inet4Address }?.let { return it.hostAddress }
        }
        val h = si.host
        return if (h is Inet4Address) h.hostAddress else null
    }

    private fun succeed(host: String, port: Int) {
        val cb = onResult ?: return
        cleanup()
        main.post { cb(host, port) }
    }

    private fun fail(msg: String) {
        val cb = onError ?: return
        cleanup()
        main.post { cb(msg) }
    }

    private fun cleanup() {
        main.removeCallbacks(timeout)
        onResult = null
        onError = null
        stop()
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
    }

    companion object {
        const val CONNECT = "_adb-tls-connect._tcp."
        const val PAIRING = "_adb-tls-pairing._tcp."
    }
}
