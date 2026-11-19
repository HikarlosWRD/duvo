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

enum class StickyState { OFF, ONCE, LOCKED }

/** Sticky modifiers: tap = ONCE, second tap = LOCKED, third = OFF. */
class StickyModifiers {
    private val mods = listOf(NativeBridge.MOD_SHIFT, NativeBridge.MOD_CTRL, NativeBridge.MOD_ALT, NativeBridge.MOD_META)
    private val states = mods.associateWith { StickyState.OFF }.toMutableMap()

    val state: Map<Byte, StickyState> get() = states.toMap()

    fun tap(mod: Byte) {
        val cur = states[mod] ?: return
        states[mod] = when (cur) {
            StickyState.OFF -> StickyState.ONCE
            StickyState.ONCE -> StickyState.LOCKED
            StickyState.LOCKED -> StickyState.OFF
        }
    }

    /** Mask of ONCE+LOCKED modifiers; clears the ONCE ones. */
    fun consumeForKey(): Byte {
        var mask = 0
        for (m in mods) {
            when (states[m]) {
                StickyState.ONCE -> { mask = mask or m.toInt(); states[m] = StickyState.OFF }
                StickyState.LOCKED -> mask = mask or m.toInt()
                else -> {}
            }
        }
        return mask.toByte()
    }
}
