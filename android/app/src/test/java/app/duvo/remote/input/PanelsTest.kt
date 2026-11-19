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

class PanelsTest {
    private fun ids(p: PanelId) = Panels.actions(p).map { it.id }
    private fun act(p: PanelId, id: String) = Panels.actions(p).first { it.id == id }

    @Test fun `ids exatos de cada painel`() {
        assertEquals(listOf("play_pause", "next", "prev", "stop", "vol_up", "vol_down", "mute"), ids(PanelId.MEDIA))
        assertEquals(
            listOf("back", "forward", "refresh", "new_tab", "close_tab", "reopen_tab", "next_tab", "prev_tab",
                "zoom_in", "zoom_out", "zoom_reset", "address", "fullscreen"),
            ids(PanelId.BROWSER),
        )
        assertEquals(listOf("next", "prev", "start", "start_current", "exit", "black"), ids(PanelId.PRESENTATION))
        assertEquals(emptyList<String>(), ids(PanelId.KEYBOARD))
    }

    @Test fun `teclas e modificadores`() {
        assertEquals(listOf(Vk.MEDIA_PLAY_PAUSE), act(PanelId.MEDIA, "play_pause").keys)
        assertEquals(listOf(Vk.LEFT), act(PanelId.BROWSER, "back").keys)
        assertEquals(NativeBridge.MOD_ALT, act(PanelId.BROWSER, "back").modifiers)
        assertEquals(listOf(Vk.N0), act(PanelId.BROWSER, "zoom_reset").keys)
        assertEquals(NativeBridge.MOD_CTRL, act(PanelId.BROWSER, "zoom_reset").modifiers)
        assertEquals((NativeBridge.MOD_CTRL.toInt() or NativeBridge.MOD_SHIFT.toInt()).toByte(), act(PanelId.BROWSER, "reopen_tab").modifiers)
        assertEquals(NativeBridge.MOD_SHIFT, act(PanelId.PRESENTATION, "start_current").modifiers)
        assertEquals(listOf(Vk.B), act(PanelId.PRESENTATION, "black").keys)
        assertEquals(0.toByte(), act(PanelId.PRESENTATION, "black").modifiers)
        assertEquals(listOf(Vk.PGDN), act(PanelId.PRESENTATION, "next").keys)
    }

    @Test fun `rotulos em portugues`() {
        assertEquals("Reproduzir/Pausar", act(PanelId.MEDIA, "play_pause").label)
        assertEquals("Tela preta", act(PanelId.PRESENTATION, "black").label)
        assertEquals("Iniciar do slide atual", act(PanelId.PRESENTATION, "start_current").label)
        assertEquals("Aba anterior", act(PanelId.BROWSER, "prev_tab").label)
    }
}
