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
 */package app.duvo.remote.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoConnectTest {
    private val pc = PairedHost("U1", "PC", listOf("192.168.0.10"), 40989, 5)

    @Test fun `sem pareados fica na lista`() = assertNull(autoConnectTarget(true, false, null))
    @Test fun `com pareado vai para remote`() = assertEquals("U1", autoConnectTarget(true, false, pc))
    @Test fun `deep link nao dispara autoconnect`() = assertNull(autoConnectTarget(true, true, pc))
    @Test fun `recriacao da activity nao dispara de novo`() = assertNull(autoConnectTarget(false, false, pc))
}
