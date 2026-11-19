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

enum class Diagnosis { OK, DIFFERENT_SUBNET_LIKELY_GUEST, HOST_OFF_OR_FIREWALL, NO_WIFI }

private fun ipv4ToInt(s: String): Int? {
    val p = s.trim().split('.')
    if (p.size != 4) return null
    var r = 0
    for (x in p) {
        val v = x.toIntOrNull() ?: return null
        if (v !in 0..255) return null
        r = (r shl 8) or v
    }
    return r
}

/**
 * Pure decision: why could the phone not reach the host?
 * Precedence: no Wi-Fi, then any successful probe (OK), then no host IPv4 in the phone's subnet
 * (client isolation / guest network), else the host itself (off or firewalled).
 * Without a phone IPv4 the subnet cannot be judged, so it falls to reachability only.
 */
fun diagnose(
    phoneIps: List<String>,
    phonePrefixLen: Int,
    hostIps: List<String>,
    tcpResults: Map<String, Boolean>,
    onWifi: Boolean,
): Diagnosis {
    if (!onWifi) return Diagnosis.NO_WIFI
    if (tcpResults.values.any { it }) return Diagnosis.OK
    val phones = phoneIps.mapNotNull(::ipv4ToInt)
    if (phones.isEmpty()) return Diagnosis.HOST_OFF_OR_FIREWALL
    val mask = if (phonePrefixLen <= 0) 0 else if (phonePrefixLen >= 32) -1 else -1 shl (32 - phonePrefixLen)
    val hosts = hostIps.mapNotNull(::ipv4ToInt)
    if (hosts.isEmpty()) return Diagnosis.HOST_OFF_OR_FIREWALL // nothing to compare (IPv6 only): do not blame the network
    val sameSubnet = hosts.any { h -> phones.any { (it and mask) == (h and mask) } }
    return if (sameSubnet) Diagnosis.HOST_OFF_OR_FIREWALL else Diagnosis.DIFFERENT_SUBNET_LIKELY_GUEST
}

fun Diagnosis.message(): String = when (this) {
    Diagnosis.OK -> ""
    Diagnosis.NO_WIFI -> "Seu celular não está no Wi-Fi. Conecte-se à mesma rede do PC."
    Diagnosis.DIFFERENT_SUBNET_LIKELY_GUEST ->
        "Seu celular parece estar em outra rede (talvez a rede de convidados). Conecte-se à rede principal, a mesma do PC."
    Diagnosis.HOST_OFF_OR_FIREWALL ->
        "O PC não respondeu. Verifique se o Duvo está aberto no PC e se o firewall do Windows permite o Duvo."
}
