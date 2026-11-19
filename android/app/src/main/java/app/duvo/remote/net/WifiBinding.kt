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

package app.duvo.remote.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Ref-counted process binding, the unit-testable decision part of [WifiBinding]: the first user binds
 * the process to the Wi-Fi network returned by [find], the last one to release unbinds. When there is
 * no Wi-Fi nothing is bound (the diagnosis already reports NO_WIFI). A bound network that is lost
 * ([onLost]) is dropped; the next [acquire] or [Lease.refresh] binds whatever Wi-Fi exists then.
 */
class WifiBindingCore<N : Any>(private val find: () -> N?, private val bind: (N?) -> Boolean) {
    private var users = 0
    private var bound: N? = null

    /** One user's hold on the binding; [release] is idempotent (a flow may release, then onCleared again). */
    inner class Lease internal constructor(private var counted: Boolean) {
        private var released = false

        /** The network the process is bound to now for this user (null: default network). */
        val network: N? get() = synchronized(this@WifiBindingCore) { if (counted && !released) bound else null }

        /**
         * Re-resolves the Wi-Fi network (a long-lived user, e.g. each reconnection of the remote control):
         * rebinds when Wi-Fi changed, unbinds when there is none, and turns a lease taken without Wi-Fi
         * into a counted one once Wi-Fi appears. Returns the network to use now.
         */
        fun refresh(): N? = synchronized(this@WifiBindingCore) {
            if (released) return@synchronized null
            if (!counted) {
                bindFound()
                if (bound == null) return@synchronized null
                counted = true
                users++
                return@synchronized bound
            }
            val net = find()
            if (net != null && net == bound) return@synchronized bound
            if (net != null && bind(net)) {
                bound = net
            } else if (bound != null) {
                bound = null
                bind(null)
            }
            bound
        }

        fun release() = synchronized(this@WifiBindingCore) {
            if (released) return@synchronized
            released = true
            if (!counted) return@synchronized
            users--
            if (users == 0 && bound != null) {
                bind(null)
                bound = null
            }
        }
    }

    private fun bindFound() {
        if (bound != null) return
        val net = find()
        if (net != null && bind(net)) bound = net
    }

    /**
     * The user count only grows after a successful find+bind; if [bind] throws or returns false the
     * lease carries no network (callers use the default one) and nothing stays bound.
     */
    @Synchronized
    fun acquire(): Lease {
        bindFound()
        if (bound == null) return Lease(counted = false)
        users++
        return Lease(counted = true)
    }

    /** [net] disconnected: if it is the bound one, unbind so sockets stop failing on a dead network. */
    @Synchronized
    fun onLost(net: N) {
        if (net != bound) return
        bound = null
        bind(null)
    }
}

/**
 * While pairing, streaming or remote-controlling, binds the whole process to the Wi-Fi link that the
 * diagnosis probes: OkHttp and moonlight-common-c's native sockets otherwise use the default network,
 * which is cellular with a LAN-only Wi-Fi or a VPN. [ConnectivityManager.bindProcessToNetwork] is
 * process-wide, hence the ref count shared by every user. A Wi-Fi network that goes away is dropped
 * (NetworkCallback.onLost) so the next acquire/refresh binds the new one.
 */
object WifiBinding {
    @Volatile private var core: WifiBindingCore<Network>? = null

    fun acquire(context: Context): WifiBindingCore<Network>.Lease = core(context).acquire()

    private fun core(context: Context): WifiBindingCore<Network> = core ?: synchronized(this) {
        core ?: run {
            val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            // Low-latency Wi-Fi while a connection is open: no power-save sleeping between packets.
            val lock = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                ?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "duvo-low-latency")
                ?.also { it.setReferenceCounted(false) }
            val c = WifiBindingCore<Network>(
                find = { cm?.let(::findWifi) },
                bind = {
                    runCatching { if (it != null) { if (lock?.isHeld == false) lock.acquire() } else if (lock?.isHeld == true) lock.release() }
                    cm?.bindProcessToNetwork(it) ?: false
                },
            )
            runCatching {
                cm?.registerNetworkCallback(
                    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onLost(network: Network) = c.onLost(network)
                    },
                )
            }
            c.also { core = it }
        }
    }
}
