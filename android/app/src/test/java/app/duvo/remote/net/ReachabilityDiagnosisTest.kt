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
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachabilityDiagnosisTest {
    @Test fun `celular em 192_168_2_x e host em 192_168_0_x sem conexao vira guest`() {
        val d = diagnose(listOf("192.168.2.50"), 24, listOf("192.168.0.10"), mapOf("192.168.0.10" to false), onWifi = true)
        assertEquals(Diagnosis.DIFFERENT_SUBNET_LIKELY_GUEST, d)
        assertTrue(d.message().contains("rede de convidados"))
    }

    @Test fun `mesma subnet sem resposta vira HOST_OFF_OR_FIREWALL`() {
        val d = diagnose(listOf("192.168.0.50"), 24, listOf("192.168.0.10"), mapOf("192.168.0.10" to false), onWifi = true)
        assertEquals(Diagnosis.HOST_OFF_OR_FIREWALL, d)
        assertTrue(d.message().contains("firewall"))
        assertTrue(d.message().contains("Duvo está aberto"))
    }

    @Test fun `sem wifi vira NO_WIFI`() {
        val d = diagnose(emptyList(), 24, listOf("192.168.0.10"), emptyMap(), onWifi = false)
        assertEquals(Diagnosis.NO_WIFI, d)
        assertTrue(d.message().contains("Wi-Fi"))
    }

    @Test fun `qualquer IP respondeu vira OK`() {
        val d = diagnose(listOf("192.168.2.50"), 24, listOf("10.0.0.1", "192.168.0.10"), mapOf("10.0.0.1" to false, "192.168.0.10" to true), onWifi = true)
        assertEquals(Diagnosis.OK, d)
        assertEquals("", d.message())
    }

    @Test fun `host com IP na subnet do celular entre varios nao vira guest`() {
        val d = diagnose(listOf("192.168.0.50"), 24, listOf("10.0.0.1", "192.168.0.10"), emptyMap(), onWifi = true)
        assertEquals(Diagnosis.HOST_OFF_OR_FIREWALL, d)
    }

    @Test fun `mascara respeita o prefixo e sem IPv4 no wifi avalia so por alcance`() {
        assertEquals(Diagnosis.HOST_OFF_OR_FIREWALL, diagnose(listOf("10.0.5.2"), 16, listOf("10.0.200.1"), emptyMap(), true))
        assertEquals(Diagnosis.DIFFERENT_SUBNET_LIKELY_GUEST, diagnose(listOf("10.0.5.2"), 24, listOf("10.0.200.1"), emptyMap(), true))
        assertEquals(Diagnosis.HOST_OFF_OR_FIREWALL, diagnose(emptyList(), 24, listOf("10.0.200.1"), emptyMap(), true))
    }

    @Test fun `host sem IPv4 nao vira guest`() {
        assertEquals(Diagnosis.HOST_OFF_OR_FIREWALL, diagnose(listOf("192.168.0.5"), 24, listOf("fe80::1"), emptyMap(), true))
    }

    @Test fun `prefixo 0 e 32`() {
        assertEquals(Diagnosis.HOST_OFF_OR_FIREWALL, diagnose(listOf("10.0.0.5"), 0, listOf("192.168.0.1"), emptyMap(), true))
        assertEquals(Diagnosis.HOST_OFF_OR_FIREWALL, diagnose(listOf("10.0.0.5"), 32, listOf("10.0.0.5"), emptyMap(), true))
        assertEquals(Diagnosis.DIFFERENT_SUBNET_LIKELY_GUEST, diagnose(listOf("10.0.0.5"), 32, listOf("10.0.0.6"), emptyMap(), true))
    }
}
