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

class MirrorGesturesTest {
    // View 2000x1200 showing a 1920x1080 video: letterboxed top/bottom.
    private val rect = VideoRect.fit(2000f, 1200f, 1920, 1080)
    private val g = MirrorGestures({ rect })
    private fun t(id: Int, x: Float, y: Float, ms: Long) = TouchSample(id, x, y, ms)

    @Test fun `letterbox mapeia centro e cantos`() {
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(37.5f, rect.top, 0.01f)
        assertEquals(960 to 540, rect.toVideo(1000f, 600f).let { (x, y) -> (x + 1) / 2 * 2 to (y + 1) / 2 * 2 })
        assertEquals(0 to 0, rect.toVideo(-50f, 0f)) // outside = clamped
        assertEquals(1919 to 1079, rect.toVideo(2000f, 1200f))
    }

    @Test fun `toque curto clica onde tocou`() {
        g.down(t(0, 1000f, 600f, 0))
        val out = g.up(t(0, 1001f, 600f, 100))
        assertTrue(out[0] is PadAction.PointTo)
        assertEquals(PadAction.Click(1), out[1])
    }

    @Test fun `segurar parado e clique direito`() {
        g.down(t(0, 500f, 500f, 0))
        assertEquals(PadAction.Click(3), g.up(t(0, 500f, 500f, 700))[1])
    }

    @Test fun `arrastar segura o botao esquerdo e solta no fim`() {
        g.down(t(0, 100f, 100f, 0))
        val m = g.move(t(0, 200f, 100f, 50))
        assertEquals(PadAction.Press(1), m[1])
        assertTrue(m.last() is PadAction.PointTo)
        val u = g.up(t(0, 300f, 100f, 100))
        assertEquals(PadAction.Release(1), u.last())
    }

    @Test fun `dois dedos rolam e toque com dois dedos e clique direito`() {
        g.down(t(0, 500f, 500f, 0)); g.down(t(1, 700f, 500f, 5))
        val s = g.move(t(0, 500f, 620f, 30)) + g.move(t(1, 700f, 620f, 31))
        val v = s.filterIsInstance<PadAction.Scroll>().sumOf { it.v }
        assertTrue("finger down scrolls content down = wheel up: $v", v > 0)
        g.up(t(0, 500f, 620f, 60)); g.up(t(1, 700f, 620f, 61))

        g.down(t(0, 500f, 500f, 1000)); g.down(t(1, 700f, 500f, 1005))
        g.up(t(0, 500f, 500f, 1100))
        assertEquals(PadAction.Click(3), g.up(t(1, 700f, 500f, 1110)).last())
    }

    @Test fun `pinca vira zoom`() {
        g.down(t(0, 900f, 600f, 0)); g.down(t(1, 1100f, 600f, 5))
        val out = g.move(t(0, 800f, 600f, 30)) + g.move(t(1, 1200f, 600f, 31))
        assertTrue(out.filterIsInstance<PadAction.Zoom>().sumOf { it.steps } > 0)
        assertTrue(out.none { it is PadAction.Scroll })
    }

    @Test fun `segundo dedo durante arrasto solta o botao`() {
        g.down(t(0, 100f, 100f, 0)); g.move(t(0, 200f, 100f, 50))
        assertEquals(listOf<PadAction>(PadAction.Release(1)), g.down(t(1, 400f, 100f, 60)))
    }
}
