/*
 * Duvo - remote control and streaming for a Windows PC.
 * Copyright (C) 2026 Duvo contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package app.duvo.remote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.util.ArrayDeque
import java.util.concurrent.Executor

/** NsdManager-backed browser. Uses ServiceInfoCallback on API 34+, a serial resolve queue below. */
class AndroidNsdBrowser(context: Context) : NsdBrowser {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val direct = Executor { it.run() }

    private var lock: WifiManager.MulticastLock? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var sink: ((NsdService) -> Unit)? = null
    private var lostSink: ((String) -> Unit)? = null

    // Pre-34: NsdManager resolves one service at a time.
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val callbacks = HashMap<String, NsdManager.ServiceInfoCallback>()

    @Synchronized
    override fun start(onResolved: (NsdService) -> Unit, onLost: (String) -> Unit) {
        if (discovery != null) return
        sink = onResolved
        lostSink = onLost
        lock = wifi.createMulticastLock("duvo-mdns").apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { release() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = handleLost(serviceInfo)
            override fun onServiceFound(serviceInfo: NsdServiceInfo) = handleFound(serviceInfo)
        }
        discovery = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    @Synchronized
    override fun stop() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        if (Build.VERSION.SDK_INT >= 34) {
            callbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }
        callbacks.clear()
        queue.clear()
        resolving = false
        release()
        sink = null
        lostSink = null
    }

    private fun release() {
        lock?.let { if (it.isHeld) it.release() }
        lock = null
    }

    @Synchronized
    private fun handleFound(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = emit(serviceInfo)
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
            }
            callbacks.put(info.serviceName, cb)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            nsd.registerServiceInfoCallback(info, direct, cb)
        } else {
            queue.add(info)
            resolveNext()
        }
    }

    @Synchronized
    private fun handleLost(info: NsdServiceInfo) {
        queue.removeAll { it.serviceName == info.serviceName }
        if (Build.VERSION.SDK_INT >= 34) {
            callbacks.remove(info.serviceName)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }
        lostSink?.invoke(info.serviceName)
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val next = queue.poll() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = done()
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) { emit(serviceInfo); done() }
            private fun done() = synchronized(this@AndroidNsdBrowser) { resolving = false; resolveNext() }
        })
    }

    @Suppress("DEPRECATION")
    private fun emit(info: NsdServiceInfo) {
        val addrs = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val v4 = addrs.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
        if (v4.isEmpty()) return
        sink?.invoke(NsdService(info.serviceName, v4, info.port))
    }

    companion object { const val SERVICE_TYPE = "_duvo._tcp" }
}
