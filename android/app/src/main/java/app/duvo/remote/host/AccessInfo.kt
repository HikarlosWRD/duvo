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
package app.duvo.remote.host

/** What the PC allows this phone to do (the host's permission bits, spec §8.8). */
data class AccessInfo(val perm: Int) {
    val input get() = perm and INPUT_ALL != 0
    val windows get() = perm and WINDOWS != 0
    val power get() = perm and POWER != 0
    val clipboardSend get() = perm and CLIPBOARD_SET != 0
    val clipboardRead get() = perm and CLIPBOARD_READ != 0
    val view get() = perm and VIEW != 0
    val launch get() = perm and LAUNCH != 0

    /** Same presets as the host's device editor; anything else is "Personalizado". */
    val presetName: String get() = when (perm) {
        TOTAL -> "Acesso total"
        CONTROL -> "Controle"
        WATCH -> "Só assistir"
        0 -> "Desativado"
        else -> "Personalizado"
    }

    companion object {
        const val INPUT_ALL = 0x1F00
        const val CLIPBOARD_SET = 0x10000
        const val CLIPBOARD_READ = 0x20000
        const val WINDOWS = 0x200000
        const val POWER = 0x400000
        const val VIEW = 0x02000000
        const val LAUNCH = 0x04000000
        const val TOTAL = 0x077F1F00
        const val CONTROL = 0x07631F00
        const val WATCH = 0x03000000
    }
}
