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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerCurveTest {
    @Test fun `curva sem aceleracao e linear`() {
        val cfg = PadConfig(acceleration = false, sensitivity = 1f)
        assertEquals(Pair(10, 0), PointerCurve.apply(10f, 0f, 16, cfg))
    }

    @Test fun `sensibilidade escala sem aceleracao`() {
        val cfg = PadConfig(acceleration = false, sensitivity = 2f)
        assertEquals(Pair(20, -10), PointerCurve.apply(10f, -5f, 16, cfg))
    }

    @Test fun `curva com aceleracao cresce com velocidade`() {
        val cfg = PadConfig(acceleration = true)
        val slow = PointerCurve.apply(10f, 0f, 8, cfg).first
        val fast = PointerCurve.apply(40f, 0f, 8, cfg).first
        assertTrue("fast=$fast slow=$slow", fast > 4 * slow)
        assertTrue(fast > 40)
    }

    @Test fun `dt zero nao divide por zero`() {
        val r = PointerCurve.apply(10f, 0f, 0, PadConfig())
        assertTrue(r.first > 0)
    }
}
