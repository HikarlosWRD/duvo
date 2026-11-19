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
package app.duvo.remote.gamepad

import app.duvo.remote.stream.NativeBridge
import app.duvo.remote.stream.StreamCallbacks
import app.duvo.remote.stream.StreamConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GamepadTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Bridge : NativeBridge {
        val log = mutableListOf<String>()
        override fun start(cfg: StreamConfig, cb: StreamCallbacks) = 0
        override fun stop() {}
        override fun sendMouseMove(dx: Short, dy: Short) {}
        override fun sendMouseButton(down: Boolean, button: Int) {}
        override fun sendKeyboard(keyCode: Short, down: Boolean, modifiers: Byte) {}
        override fun sendControllerArrival(number: Int, activeMask: Int, type: Int, buttons: Int, capabilities: Int): Int {
            log += "arrive $number mask=$activeMask type=$type"; return 0
        }
        override fun sendController(
            number: Int, activeMask: Int, buttons: Int, leftTrigger: Int, rightTrigger: Int,
            lx: Short, ly: Short, rx: Short, ry: Short,
        ): Int { log += "state $number mask=$activeMask b=$buttons lt=$leftTrigger lx=$lx ly=$ly"; return 0 }
    }

    @Test fun `sender anuncia, manda so mudancas e remove pelo mask`() {
        val b = Bridge()
        val s = ControllerSender(b)
        s.connect(0, NativeBridge.CTYPE_PS, 0)
        s.update(0, GamepadState()) // unchanged: nothing
        s.update(0, GamepadState(buttons = Btn.A))
        s.connect(1, NativeBridge.CTYPE_XBOX, 0)
        s.disconnect(0)
        assertEquals(
            listOf(
                "arrive 0 mask=1 type=2", "state 0 mask=1 b=0 lt=0 lx=0 ly=0",
                "state 0 mask=1 b=4096 lt=0 lx=0 ly=0",
                "arrive 1 mask=3 type=1", "state 1 mask=3 b=0 lt=0 lx=0 ly=0",
                "state 0 mask=2 b=0 lt=0 lx=0 ly=0",
            ),
            b.log,
        )
        assertEquals(0, s.firstFreeSlot())
    }

    private val w = 2000f
    private val h = 1200f

    private fun engine(l: PadLayout = PadLayout.preset(PadStyle.XBOX)) = VirtualPadEngine(l, w, h)
    private fun at(l: PadLayout, id: String) = l.controls.first { it.id == id }.let { it.x * w to it.y * h }

    @Test fun `botao aperta e solta, deslizar troca de botao`() {
        val l = PadLayout.preset(PadStyle.XBOX)
        val e = engine(l)
        val (ax, ay) = at(l, "a")
        assertTrue(e.down(7, ax, ay))
        assertEquals(Btn.A, e.state().buttons)
        val (bx, by) = at(l, "b")
        e.move(7, bx, by)
        assertEquals(Btn.B, e.state().buttons)
        e.up(7)
        assertEquals(0, e.state().buttons)
    }

    @Test fun `area vazia nao e capturada`() {
        assertFalse(engine().down(1, 1000f, 600f))
    }

    @Test fun `analogico com zona morta e cima positivo`() {
        val l = PadLayout.preset(PadStyle.XBOX)
        val e = engine(l)
        val (cx, cy) = at(l, "ls")
        val r = l.controls.first { it.id == "ls" }.size * h / 2
        e.down(1, cx + 2f, cy)
        assertEquals(0.toShort(), e.state().lx) // dead zone
        e.move(1, cx, cy - 3 * r) // far up, beyond the edge: full up
        assertEquals(32767.toShort(), e.state().ly)
        assertEquals(0.toShort(), e.state().lx)
        e.up(1)
        assertEquals(GamepadState(), e.state())
    }

    @Test fun `dpad 8 direcoes e gatilho`() {
        assertEquals(Btn.UP, VirtualPadEngine.dirFlags(Math.PI / 2))
        assertEquals(Btn.UP or Btn.LEFT, VirtualPadEngine.dirFlags(3 * Math.PI / 4))
        assertEquals(Btn.RIGHT, VirtualPadEngine.dirFlags(0.0))
        val l = PadLayout.preset(PadStyle.XBOX)
        val e = engine(l)
        val (tx, ty) = at(l, "rt")
        e.down(3, tx, ty)
        assertEquals(255, e.state().rightTrigger)
    }

    @Test fun `switch troca rotulos por posicao e opcao A-B`() {
        val pos = PadLayout.preset(PadStyle.SWITCH)
        assertEquals("A" to Btn.B, pos.controls.first { it.id == "b" }.let { it.label to it.button })
        val swapped = PadLayout.preset(PadStyle.SWITCH, swapAB = true)
        assertEquals("A" to Btn.A, swapped.controls.first { it.id == "b" }.let { it.label to it.button })
    }

    @Test fun `layout vai e volta do JSON e importacao invalida e limpa`() {
        val l = PadLayout.preset(PadStyle.PLAYSTATION).copy(name = "Meu")
        assertEquals(l, PadLayout.fromJson(JSONObject(l.toJson().toString())))
        val bad = JSONObject(l.toJson().toString())
        bad.getJSONArray("controls").getJSONObject(0).put("x", 9.0).put("size", -1.0).put("button", -1)
        val fixed = PadLayout.fromJson(bad).controls[0]
        assertEquals(1f, fixed.x); assertEquals(0.05f, fixed.size); assertEquals(Btn.STANDARD, fixed.button)
        val err = runCatching { PadLayout.fromJson(JSONObject("{\"style\":\"NOPE\"}")) }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
    }

    @Test fun `store salva, lista, escolhe atual e apaga`() {
        val st = LayoutStore(tmp.newFolder("pads"))
        assertEquals(PadStyle.XBOX, st.active().style)
        st.save(PadLayout.preset(PadStyle.SWITCH).copy(name = "Corrida"))
        st.setCurrent("Corrida")
        assertEquals("Corrida", st.active().name)
        st.setCurrent(PadLayout.preset(PadStyle.PLAYSTATION).name)
        assertEquals(PadStyle.PLAYSTATION, st.active().style)
        st.delete("Corrida")
        assertTrue(st.list().isEmpty())
    }

    @Test fun `mapeamento do controle fisico`() {
        assertEquals(Btn.A, PadMapping.buttonFor(android.view.KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(Btn.GUIDE, PadMapping.buttonFor(android.view.KeyEvent.KEYCODE_BUTTON_MODE))
        assertEquals(0, PadMapping.buttonFor(android.view.KeyEvent.KEYCODE_A))
        assertEquals(1, PadMapping.triggerFor(android.view.KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals(0.toShort(), PadMapping.stick(0.03f))
        assertEquals(32767.toShort(), PadMapping.stick(1.5f))
        assertEquals(255, PadMapping.trigger(1f))
        assertEquals(Btn.UP or Btn.LEFT, PadMapping.hat(-1f, -1f))
        assertEquals(NativeBridge.CTYPE_PS, PadMapping.ctypeFor(0x054C))
        assertEquals(NativeBridge.CTYPE_XBOX, PadMapping.ctypeFor(0x045E))
    }
}
