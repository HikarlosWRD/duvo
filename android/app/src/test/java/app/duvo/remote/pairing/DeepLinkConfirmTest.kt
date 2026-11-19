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

package app.duvo.remote.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class DeepLinkConfirmTest {
    private fun payload(ips: List<String>, name: String) =
        PairPayload(ips, 40989, "1234", "p".repeat(24), "a".repeat(64), name)

    @Test fun `link de pareamento pede confirmacao com nome e IP`() {
        assertEquals("Parear com PC Sala?\nIPs: 192.168.0.10", deepLinkConfirmText(payload(listOf("192.168.0.10"), "PC Sala")))
    }

    @Test fun `todos os IPs do link aparecem na confirmacao`() {
        assertEquals(
            "Parear com PC?\nIPs: 192.168.0.10, 10.0.0.2",
            deepLinkConfirmText(payload(listOf("192.168.0.10", "10.0.0.2"), "PC")),
        )
    }
}
