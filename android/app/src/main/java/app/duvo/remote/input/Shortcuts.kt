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

data class Shortcut(val id: String, val label: String, val keys: List<Short>, val modifiers: Byte, val confirm: Boolean = false)

object Shortcuts {
    private fun m(vararg b: Byte): Byte = b.fold(0) { a, x -> a or x.toInt() }.toByte()

    val all: List<Shortcut> = listOf(
        Shortcut("copy", "Copiar", listOf(Vk.C), NativeBridge.MOD_CTRL),
        Shortcut("paste", "Colar", listOf(Vk.V), NativeBridge.MOD_CTRL),
        Shortcut("cut", "Recortar", listOf(Vk.X), NativeBridge.MOD_CTRL),
        Shortcut("undo", "Desfazer", listOf(Vk.Z), NativeBridge.MOD_CTRL),
        Shortcut("redo", "Refazer", listOf(Vk.Y), NativeBridge.MOD_CTRL),
        Shortcut("select_all", "Selecionar tudo", listOf(Vk.A), NativeBridge.MOD_CTRL),
        Shortcut("alt_tab", "Alt+Tab", listOf(Vk.TAB), NativeBridge.MOD_ALT),
        Shortcut("win_d", "Win+D", listOf(Vk.D), NativeBridge.MOD_META),
        Shortcut("task_manager", "Ctrl+Shift+Esc", listOf(Vk.ESC), m(NativeBridge.MOD_CTRL, NativeBridge.MOD_SHIFT)),
        Shortcut("alt_f4", "Alt+F4", listOf(Vk.F4), NativeBridge.MOD_ALT, confirm = true),
        Shortcut("win_l", "Win+L", listOf(Vk.L), NativeBridge.MOD_META),
    )
}
