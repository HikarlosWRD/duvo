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
package app.duvo.remote.input

import app.duvo.remote.stream.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Test

class VkFlagsTest {
    @Test fun `teclas de midia volume e navegador nao sao normalizadas`() {
        val keys = listOf(
            Vk.BROWSER_BACK, Vk.BROWSER_FWD, Vk.BROWSER_REFRESH, Vk.VOL_MUTE, Vk.VOL_DOWN, Vk.VOL_UP,
            Vk.MEDIA_NEXT, Vk.MEDIA_PREV, Vk.MEDIA_STOP, Vk.MEDIA_PLAY_PAUSE,
        )
        keys.forEach { assertEquals(NativeBridge.FLAG_NON_NORMALIZED, Vk.flagsFor(it)) }
    }

    @Test fun `teclas comuns usam scan code`() {
        listOf(Vk.ENTER, Vk.CONTROL, Vk.LWIN, Vk.OEM_PLUS, Vk.N0, 0x41.toShort()).forEach {
            assertEquals(0.toByte(), Vk.flagsFor(it))
        }
    }
}
