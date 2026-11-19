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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress

/** The two capability bits that decide whether a network is the physical Wi-Fi link. */
data class CapsView(val wifiTransport: Boolean, val notVpn: Boolean)

/** First Wi-Fi network that is not a VPN (a VPN over Wi-Fi also reports TRANSPORT_WIFI); null if none. */
fun <N> pickWifi(networks: List<N>, caps: (N) -> CapsView?): N? =
    networks.firstOrNull { n -> caps(n)?.let { it.wifiTransport && it.notVpn } == true }

/** The physical Wi-Fi network (see [pickWifi]); not activeNetwork, which stays cellular with a LAN-only Wi-Fi or a VPN. */
fun findWifi(cm: ConnectivityManager): Network? {
    @Suppress("DEPRECATION")
    val all = cm.allNetworks.toList()
    return pickWifi(all) { n ->
        cm.getNetworkCapabilities(n)?.let {
            CapsView(it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        }
    }
}

/** Android glue for [diagnose]: reads the Wi-Fi link and probes the host's IPs; never takes more than ~[PROBE_TIMEOUT_MS]. */
object ConnectionDiagnostics {
    const val PROBE_TIMEOUT_MS = 1500

    suspend fun run(context: Context, hostIps: List<String>, port: Int): Diagnosis = withContext(Dispatchers.IO) {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val wifi: Network? = cm?.let(::findWifi)
        val v4 = if (wifi == null) emptyList() else cm.getLinkProperties(wifi)?.linkAddresses.orEmpty()
            .filter { it.address is Inet4Address }
        val phoneIps = v4.mapNotNull { it.address.hostAddress }
        val prefix = v4.firstOrNull()?.prefixLength ?: 24
        val results = if (wifi == null) emptyMap() else probe(wifi, hostIps, port)
        diagnose(phoneIps, prefix, hostIps, results, onWifi = wifi != null)
    }

    /** Plain TCP connect, bound to the Wi-Fi network, to every IP in parallel (<= [PROBE_TIMEOUT_MS] each). */
    suspend fun probe(net: Network, ips: List<String>, port: Int): Map<String, Boolean> = coroutineScope {
        ips.map { ip -> ip to async { tcp(net, ip, port) } }.associate { (ip, d) -> ip to d.await() }
    }

    private fun tcp(net: Network, ip: String, port: Int): Boolean = try {
        net.socketFactory.createSocket().use { it.connect(InetSocketAddress(ip, port), PROBE_TIMEOUT_MS) }
        true
    } catch (_: Exception) {
        false
    }
}
