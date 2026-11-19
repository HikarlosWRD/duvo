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

class StickyModifiersTest {
    @Test fun `ctrl uma vez aplica so na proxima tecla`() {
        val s = StickyModifiers()
        s.tap(NativeBridge.MOD_CTRL)
        assertEquals(StickyState.ONCE, s.state[NativeBridge.MOD_CTRL])
        assertEquals(NativeBridge.MOD_CTRL, s.consumeForKey())
        assertEquals(0.toByte(), s.consumeForKey())
        assertEquals(StickyState.OFF, s.state[NativeBridge.MOD_CTRL])
    }

    @Test fun `ctrl duas vezes trava ate terceiro toque`() {
        val s = StickyModifiers()
        s.tap(NativeBridge.MOD_CTRL)
        s.tap(NativeBridge.MOD_CTRL)
        assertEquals(StickyState.LOCKED, s.state[NativeBridge.MOD_CTRL])
        assertEquals(NativeBridge.MOD_CTRL, s.consumeForKey())
        assertEquals(NativeBridge.MOD_CTRL, s.consumeForKey())
        s.tap(NativeBridge.MOD_CTRL)
        assertEquals(StickyState.OFF, s.state[NativeBridge.MOD_CTRL])
        assertEquals(0.toByte(), s.consumeForKey())
    }
}
