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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackpadGesturesTest {
    private fun s(id: Int, x: Float, y: Float, t: Long) = TouchSample(id, x, y, t)

    private val left = NativeBridge.BUTTON_LEFT
    private val middle = NativeBridge.BUTTON_MIDDLE
    private val right = NativeBridge.BUTTON_RIGHT

    @Test fun `toque com tremor ainda e clique`() {
        val g = TrackpadGestures(PadConfig())
        val out = g.down(s(0, 100f, 100f, 0)) + g.move(s(0, 105f, 100f, 60)) + g.up(s(0, 105f, 100f, 120))
        assertEquals(listOf<PadAction>(PadAction.Click(left)), out)
    }

    @Test fun `arrasto curto nao vira clique`() {
        val g = TrackpadGestures(PadConfig())
        val out = g.down(s(0, 100f, 100f, 0)) + g.move(s(0, 130f, 100f, 50)) + g.up(s(0, 130f, 100f, 60))
        assertTrue(out.any { it is PadAction.Move })
        assertTrue(out.none { it is PadAction.Click })
    }

    @Test fun `dois dedos toque clique direito`() {
        val g = TrackpadGestures(PadConfig())
        val out = g.down(s(0, 100f, 100f, 0)) + g.down(s(1, 200f, 100f, 10)) +
            g.up(s(0, 100f, 100f, 100)) + g.up(s(1, 200f, 100f, 110))
        assertEquals(listOf<PadAction>(PadAction.Click(right)), out)
    }

    @Test fun `tres dedos toque clique do meio`() {
        val g = TrackpadGestures(PadConfig())
        val out = g.down(s(0, 100f, 100f, 0)) + g.down(s(1, 200f, 100f, 5)) + g.down(s(2, 300f, 100f, 10)) +
            g.up(s(0, 100f, 100f, 100)) + g.up(s(1, 200f, 100f, 105)) + g.up(s(2, 300f, 100f, 110))
        assertEquals(listOf<PadAction>(PadAction.Click(middle)), out)
    }

    @Test fun `toque longo demais nao e clique`() {
        val g = TrackpadGestures(PadConfig())
        val out = g.down(s(0, 100f, 100f, 0)) + g.up(s(0, 100f, 100f, 500))
        assertTrue(out.none { it is PadAction.Click })
    }

    private fun twoFingerDrag(g: TrackpadGestures, dy: Float, steps: Int): List<PadAction> {
        val out = mutableListOf<PadAction>()
        out += g.down(s(0, 100f, 100f, 0))
        out += g.down(s(1, 200f, 100f, 0))
        for (i in 1..steps) {
            val t = i * 16L
            val y = 100f + dy * i / steps
            out += g.move(s(0, 100f, y, t))
            out += g.move(s(1, 200f, y, t))
        }
        val t = (steps + 1) * 16L
        out += g.up(s(0, 100f, 100f + dy, t))
        out += g.up(s(1, 200f, 100f + dy, t))
        return out
    }

    @Test fun `dois dedos arrastar rola sem clicar`() {
        val out = twoFingerDrag(TrackpadGestures(PadConfig()), 100f, 10)
        val scrolls = out.filterIsInstance<PadAction.Scroll>()
        assertTrue(scrolls.isNotEmpty())
        assertTrue(scrolls.sumOf { it.v } < 0)
        assertEquals(0, scrolls.sumOf { it.h })
        assertTrue(out.all { it is PadAction.Scroll })
        // 100 px a 60 px/notch = 200 unidades
        assertEquals(-200, scrolls.sumOf { it.v }, )
    }

    @Test fun `dois dedos para cima rola positivo`() {
        val out = twoFingerDrag(TrackpadGestures(PadConfig()), -60f, 6)
        assertEquals(120, out.filterIsInstance<PadAction.Scroll>().sumOf { it.v })
    }

    @Test fun `pinca para fora da zoom positivo sem clique`() {
        val g = TrackpadGestures(PadConfig())
        val out = mutableListOf<PadAction>()
        out += g.down(s(0, 200f, 100f, 0))
        out += g.down(s(1, 300f, 100f, 0))
        for (i in 1..10) {
            val t = i * 16L
            out += g.move(s(0, 200f - 10f * i, 100f, t))
            out += g.move(s(1, 300f + 10f * i, 100f, t))
        }
        out += g.up(s(0, 100f, 100f, 200))
        out += g.up(s(1, 400f, 100f, 200))
        assertTrue(out.filterIsInstance<PadAction.Zoom>().sumOf { it.steps } > 0)
        assertTrue(out.none { it is PadAction.Click })
        assertTrue(out.none { it is PadAction.Scroll })
    }

    @Test fun `pinca para dentro da zoom negativo`() {
        val g = TrackpadGestures(PadConfig())
        val out = mutableListOf<PadAction>()
        out += g.down(s(0, 100f, 100f, 0))
        out += g.down(s(1, 400f, 100f, 0))
        for (i in 1..10) {
            val t = i * 16L
            out += g.move(s(0, 100f + 10f * i, 100f, t))
            out += g.move(s(1, 400f - 10f * i, 100f, t))
        }
        assertTrue(out.filterIsInstance<PadAction.Zoom>().sumOf { it.steps } < 0)
    }

    @Test fun `segurar 1s trava e proximo toque solta`() {
        val g = TrackpadGestures(PadConfig())
        assertTrue(g.down(s(0, 100f, 100f, 0)).isEmpty())
        assertTrue(g.tick(1000).isEmpty())
        assertEquals(listOf<PadAction>(PadAction.Press(left)), g.tick(1001))
        assertTrue(g.tick(1500).isEmpty())
        assertTrue(g.up(s(0, 100f, 100f, 1600)).isEmpty())
        assertEquals(listOf<PadAction>(PadAction.Release(left)), g.down(s(0, 100f, 100f, 3000)))
        assertTrue(g.up(s(0, 100f, 100f, 3050)).isEmpty())
    }

    @Test fun `trava dispara no up apos o tempo`() {
        val g = TrackpadGestures(PadConfig())
        g.down(s(0, 100f, 100f, 0))
        assertEquals(listOf<PadAction>(PadAction.Press(left)), g.up(s(0, 101f, 100f, 1200)))
    }

    @Test fun `arrastar depois de travar move com botao preso`() {
        val g = TrackpadGestures(PadConfig())
        g.down(s(0, 100f, 100f, 0))
        val out = g.move(s(0, 102f, 100f, 1100))
        assertEquals(PadAction.Press(left), out.first())
        assertTrue(out.any { it is PadAction.Move })
    }

    @Test fun `tres dedos para cima Win+Tab`() {
        assertEquals(
            listOf<PadAction>(PadAction.Chord(listOf(0x09.toShort()), NativeBridge.MOD_META)),
            threeFingerSwipe(0f, -100f),
        )
    }

    @Test fun `tres dedos para baixo Win+D`() {
        assertEquals(
            listOf<PadAction>(PadAction.Chord(listOf(0x44.toShort()), NativeBridge.MOD_META)),
            threeFingerSwipe(0f, 100f),
        )
    }

    @Test fun `tres dedos esquerda Alt+Tab`() {
        assertEquals(
            listOf<PadAction>(PadAction.Chord(listOf(0x09.toShort()), NativeBridge.MOD_ALT)),
            threeFingerSwipe(-100f, 0f),
        )
    }

    @Test fun `tres dedos direita Alt+Shift+Tab`() {
        val mods = (NativeBridge.MOD_ALT.toInt() or NativeBridge.MOD_SHIFT.toInt()).toByte()
        assertEquals(
            listOf<PadAction>(PadAction.Chord(listOf(0x09.toShort()), mods)),
            threeFingerSwipe(100f, 0f),
        )
    }

    private fun threeFingerSwipe(dx: Float, dy: Float): List<PadAction> {
        val g = TrackpadGestures(PadConfig())
        val out = mutableListOf<PadAction>()
        for (i in 0..2) out += g.down(s(i, 100f + 100f * i, 300f, 0))
        for (step in 1..10) {
            val t = step * 16L
            for (i in 0..2) out += g.move(s(i, 100f + 100f * i + dx * step / 10, 300f + dy * step / 10, t))
        }
        for (i in 0..2) out += g.up(s(i, 100f + 100f * i + dx, 300f + dy, 200))
        return out
    }

    @Test fun `canhoto troca esquerdo e direito`() {
        val g = TrackpadGestures(PadConfig(leftHanded = true))
        val one = g.down(s(0, 100f, 100f, 0)) + g.up(s(0, 100f, 100f, 80))
        assertEquals(listOf<PadAction>(PadAction.Click(right)), one)
        val two = g.down(s(0, 100f, 100f, 1000)) + g.down(s(1, 200f, 100f, 1000)) +
            g.up(s(0, 100f, 100f, 1080)) + g.up(s(1, 200f, 100f, 1080))
        assertEquals(listOf<PadAction>(PadAction.Click(left)), two)
    }

    @Test fun `dedo restante apos multitoque nao move cursor`() {
        val g = TrackpadGestures(PadConfig())
        g.down(s(0, 100f, 100f, 0))
        g.down(s(1, 200f, 100f, 0))
        g.up(s(1, 200f, 100f, 300))
        assertFalse(g.move(s(0, 150f, 100f, 320)).any { it is PadAction.Move })
    }

    @Test fun `reset solta arrasto travado e limpa estado`() {
        val g = TrackpadGestures(PadConfig())
        g.down(s(0, 100f, 100f, 0))
        g.tick(1500)
        assertEquals(listOf<PadAction>(PadAction.Release(left)), g.reset())
        assertTrue(g.reset().isEmpty())
        val out = g.down(s(0, 50f, 50f, 5000)) + g.up(s(0, 50f, 50f, 5080))
        assertEquals(listOf<PadAction>(PadAction.Click(left)), out)
    }

    @Test fun `reset com dedos ativos descarta ponteiros obsoletos`() {
        val g = TrackpadGestures(PadConfig())
        g.down(s(0, 100f, 100f, 0))
        g.down(s(1, 200f, 100f, 0))
        assertTrue(g.reset().isEmpty())
        val out = g.down(s(0, 50f, 50f, 9000)) + g.up(s(0, 50f, 50f, 9050))
        assertEquals(listOf<PadAction>(PadAction.Click(left)), out)
    }

    @Test fun `movimento lento com sensibilidade baixa acumula`() {
        val g = TrackpadGestures(PadConfig(sensitivity = 0.3f, acceleration = false))
        g.down(s(0, 0f, 0f, 0))
        g.move(s(0, 10f, 0f, 10)) // passa o slop
        var total = 0
        for (i in 1..10) {
            total += g.move(s(0, 10f + i, 0f, 10L + i * 16)).filterIsInstance<PadAction.Move>().sumOf { it.dx }
        }
        assertEquals(3, total)
    }

    @Test fun `pinca com um dedo parado que nao reporta ainda da zoom`() {
        val g = TrackpadGestures(PadConfig())
        val out = mutableListOf<PadAction>()
        out += g.down(s(0, 200f, 100f, 0))
        out += g.down(s(1, 300f, 100f, 0))
        for (i in 1..10) out += g.move(s(1, 300f + 10f * i, 100f, i * 16L))
        assertTrue(out.filterIsInstance<PadAction.Zoom>().sumOf { it.steps } > 0)
    }

    @Test fun `dois dedos para a direita rola h positivo`() {
        val g = TrackpadGestures(PadConfig())
        val out = mutableListOf<PadAction>()
        out += g.down(s(0, 100f, 100f, 0))
        out += g.down(s(1, 100f, 200f, 0))
        for (i in 1..6) {
            out += g.move(s(0, 100f + 10f * i, 100f, i * 16L))
            out += g.move(s(1, 100f + 10f * i, 200f, i * 16L))
        }
        val sc = out.filterIsInstance<PadAction.Scroll>()
        assertEquals(120, sc.sumOf { it.h })
        assertEquals(0, sc.sumOf { it.v })
    }
}
