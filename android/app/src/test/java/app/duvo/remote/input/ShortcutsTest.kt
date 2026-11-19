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
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutsTest {
    @Test fun `chord ctrl shift esc ordem correta`() {
        val mods = (NativeBridge.MOD_CTRL.toInt() or NativeBridge.MOD_SHIFT.toInt()).toByte()
        val ev = chordEvents(listOf(Vk.ESC), mods)
        assertEquals(
            listOf(
                KeyEvent(Vk.CONTROL, true, mods), KeyEvent(Vk.SHIFT, true, mods),
                KeyEvent(Vk.ESC, true, mods), KeyEvent(Vk.ESC, false, mods),
                KeyEvent(Vk.SHIFT, false, mods), KeyEvent(Vk.CONTROL, false, mods),
            ),
            ev,
        )
    }

    @Test fun `alt f4 exige confirmacao`() {
        assertTrue(Shortcuts.all.first { it.id == "alt_f4" }.confirm)
    }

    @Test fun `lista de atalhos exata`() {
        assertEquals(
            listOf("copy", "paste", "cut", "undo", "redo", "select_all", "alt_tab", "win_d", "task_manager", "alt_f4", "win_l"),
            Shortcuts.all.map { it.id },
        )
    }
}
