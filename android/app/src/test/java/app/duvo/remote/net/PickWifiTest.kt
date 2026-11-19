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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickWifiTest {
    private val caps = mapOf(
        "cell" to CapsView(wifiTransport = false, notVpn = true),
        "vpn" to CapsView(wifiTransport = true, notVpn = false),
        "wifi" to CapsView(wifiTransport = true, notVpn = true),
    )

    @Test fun `Wi-Fi sem VPN e escolhido mesmo depois de uma VPN sobre Wi-Fi`() {
        assertEquals("wifi", pickWifi(listOf("cell", "vpn", "wifi")) { caps[it] })
    }

    @Test fun `so VPN ou celular resulta em null`() {
        assertNull(pickWifi(listOf("cell", "vpn")) { caps[it] })
        assertNull(pickWifi(emptyList<String>()) { caps[it] })
        assertNull(pickWifi(listOf("x")) { null })
    }
}
