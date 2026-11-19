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
package app.duvo.remote.ui.remote

import app.duvo.remote.input.InputState
import app.duvo.remote.input.KeyEvent
import app.duvo.remote.input.PadAction
import app.duvo.remote.input.PanelId
import app.duvo.remote.input.Panels
import app.duvo.remote.input.RemoteInput
import app.duvo.remote.input.RemotePrefs
import app.duvo.remote.input.Shortcuts
import app.duvo.remote.input.StickyState
import app.duvo.remote.input.TouchSample
import app.duvo.remote.input.Vk
import app.duvo.remote.input.chordEvents
import app.duvo.remote.stream.NativeBridge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelTest {

    private class FakeInput : RemoteInput {
        override val state = MutableStateFlow<InputState>(InputState.Connected)
        val moves = mutableListOf<Pair<Int, Int>>()
        val buttons = mutableListOf<Pair<Boolean, Int>>()
        val scrolls = mutableListOf<Short>()
        val hScrolls = mutableListOf<Short>()
        val texts = mutableListOf<String>()
        val keys = mutableListOf<KeyEvent>()
        /** Everything in order, to check interleaving (e.g. Ctrl around the zoom scroll). */
        val log = mutableListOf<Any>()
        var stopped = false
        var backgroundedAt: Long? = null
        var foregrounded = 0

        override fun sendMouseMove(dx: Int, dy: Int) { moves += dx to dy; log += "move" }
        override fun sendMouseButton(down: Boolean, button: Int) { buttons += down to button; log += "button" }
        override fun sendScroll(amount: Short) { scrolls += amount; log += "scroll $amount" }
        override fun sendHScroll(amount: Short) { hScrolls += amount; log += "hscroll $amount" }
        override fun sendText(text: String) { texts += text; log += "text" }
        override fun sendKey(vk: Short, down: Boolean, modifiers: Byte) {
            val e = KeyEvent(vk, down, modifiers)
            keys += e
            log += e
        }
        override fun stop() { stopped = true }
        override fun onBackgrounded(nowMs: Long) { backgroundedAt = nowMs }
        override fun onForegrounded() { foregrounded++ }
    }

    private val dir = File(System.getProperty("java.io.tmpdir"), "duvo-remote-vm-${System.nanoTime()}").apply { mkdirs() }
    private val prefs = RemotePrefs(File(dir, "remote_prefs.json"))
    private val input = FakeInput()

    @After fun tearDown() { dir.deleteRecursively() }

    private fun TestScope.vm() = RemoteViewModel(
        input, prefs, clock = { testScheduler.currentTime }, scope = backgroundScope,
    )

    @Test fun `atalho com confirmacao nao envia antes de confirmar`() = runTest {
        val vm = vm()
        val events = mutableListOf<UiEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        runCurrent()
        val altF4 = Shortcuts.all.single { it.id == "alt_f4" }

        vm.runShortcut(altF4)
        runCurrent()

        assertTrue(input.keys.isEmpty())
        assertEquals(listOf<UiEvent>(UiEvent.Confirm(altF4)), events.filterIsInstance<UiEvent.Confirm>())

        vm.confirmShortcut(altF4)
        assertEquals(chordEvents(listOf(Vk.F4), NativeBridge.MOD_ALT), input.keys)
    }

    @Test fun `atalho sem confirmacao envia na hora`() = runTest {
        val vm = vm()
        vm.runShortcut(Shortcuts.all.single { it.id == "copy" })
        assertEquals(chordEvents(listOf(Vk.C), NativeBridge.MOD_CTRL), input.keys)
    }

    @Test fun `texto vai como sendText`() = runTest {
        val vm = vm()
        vm.onText("Olá ç")
        assertEquals(listOf("Olá ç"), input.texts)
        assertTrue(input.keys.isEmpty())
    }

    @Test fun `texto longo vai em pedacos de ate 512 sem partir pares substitutos`() {
        val emoji = "😀" // one code point, two chars
        val text = "a".repeat(511) + emoji + "b".repeat(600)
        val chunks = chunkText(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 512 })
        assertEquals("a".repeat(511), chunks[0]) // the pair moved whole to the next chunk
        assertTrue(chunks[1].startsWith(emoji))
        chunks.forEach { assertFalse(Character.isHighSurrogate(it.last())) }
        assertEquals(emptyList<String>(), chunkText(""))
    }

    @Test fun `tecla com ctrl grudento manda chord`() = runTest {
        val vm = vm()
        vm.tapSticky(NativeBridge.MOD_CTRL)
        assertEquals(StickyState.ONCE, vm.ui.value.sticky[NativeBridge.MOD_CTRL])

        vm.onKey(Vk.A)
        assertEquals(chordEvents(listOf(Vk.A), NativeBridge.MOD_CTRL), input.keys)
        assertEquals(StickyState.OFF, vm.ui.value.sticky[NativeBridge.MOD_CTRL])

        input.keys.clear()
        vm.onKey(Vk.B) // ONCE was consumed by the first key
        assertEquals(chordEvents(listOf(Vk.B), 0), input.keys)
    }

    @Test fun `letra digitada com ctrl grudento vira atalho e nao texto`() = runTest {
        val vm = vm()
        vm.tapSticky(NativeBridge.MOD_CTRL)
        vm.onText("a")
        assertEquals(chordEvents(listOf(Vk.A), NativeBridge.MOD_CTRL), input.keys)
        assertTrue(input.texts.isEmpty())
    }

    @Test fun `acao de painel soma o modificador grudento e o consome`() = runTest {
        val vm = vm()
        val newTab = Panels.actions(PanelId.BROWSER).single { it.id == "new_tab" } // Ctrl+T
        vm.tapSticky(NativeBridge.MOD_SHIFT)

        vm.runAction(newTab)

        val cs = (NativeBridge.MOD_CTRL.toInt() or NativeBridge.MOD_SHIFT.toInt()).toByte()
        assertEquals(chordEvents(listOf(Vk.T), cs), input.keys)
        assertEquals(StickyState.OFF, vm.ui.value.sticky[NativeBridge.MOD_SHIFT])
    }

    @Test fun `estado Reconnecting aparece na ui`() = runTest {
        val vm = vm()
        runCurrent()
        assertEquals(InputState.Connected, vm.ui.value.session)

        input.state.value = InputState.Reconnecting(2, 4000)
        runCurrent()

        assertEquals(InputState.Reconnecting(2, 4000), vm.ui.value.session)
        assertEquals("Reconectando… (tentativa 2, em 4 s)", sessionText(vm.ui.value.session))
    }

    @Test fun `pinca vira ctrl segurado com roda de alta resolucao`() = runTest {
        val vm = vm()
        vm.perform(PadAction.Zoom(2))
        assertEquals(
            listOf<Any>(
                KeyEvent(Vk.CONTROL, true, NativeBridge.MOD_CTRL),
                "scroll 240",
                KeyEvent(Vk.CONTROL, false, NativeBridge.MOD_CTRL),
            ),
            input.log,
        )
    }

    @Test fun `rolagem de dois dedos manda vertical e horizontal`() = runTest {
        val vm = vm()
        vm.perform(PadAction.Scroll(-120, 60))
        assertEquals(listOf<Short>(-120), input.scrolls)
        assertEquals(listOf<Short>(60), input.hScrolls)
    }

    @Test fun `toque rapido clica com o botao esquerdo`() = runTest {
        val vm = vm()
        vm.onTouch(PadTouch.Down(TouchSample(0, 100f, 100f, 0)))
        vm.onTouch(PadTouch.Up(TouchSample(0, 100f, 100f, 50)))
        assertEquals(listOf(true to NativeBridge.BUTTON_LEFT, false to NativeBridge.BUTTON_LEFT), input.buttons)
    }

    @Test fun `dedo parado trava o arraste e cancelar o toque solta o botao`() = runTest {
        val vm = vm()
        vm.onTouch(PadTouch.Down(TouchSample(0, 100f, 100f, testScheduler.currentTime)))
        advanceTimeBy(prefs.padConfig.dragLockMs + 200) // the ticker engages drag-lock with no touch event
        runCurrent()
        assertEquals(listOf(true to NativeBridge.BUTTON_LEFT), input.buttons)

        vm.onTouch(PadTouch.Cancel)
        assertEquals(listOf(true to NativeBridge.BUTTON_LEFT, false to NativeBridge.BUTTON_LEFT), input.buttons)
    }

    @Test fun `movimento com historico alimenta todos os dedos`() = runTest {
        val vm = vm()
        vm.onTouch(PadTouch.Down(TouchSample(0, 0f, 0f, 0)))
        vm.onTouch(
            PadTouch.Move(
                listOf(TouchSample(0, 20f, 0f, 10), TouchSample(0, 40f, 0f, 20), TouchSample(0, 60f, 0f, 30)),
            ),
        )
        assertTrue(input.moves.isNotEmpty())
        assertTrue(input.moves.all { it.first > 0 && it.second == 0 })
    }

    @Test fun `sair da tela solta o arraste e avisa a sessao`() = runTest {
        val vm = vm()
        vm.onTouch(PadTouch.Down(TouchSample(0, 100f, 100f, testScheduler.currentTime)))
        advanceTimeBy(prefs.padConfig.dragLockMs + 200)
        runCurrent()

        vm.onScreenStop(12_345)
        assertEquals(false to NativeBridge.BUTTON_LEFT, input.buttons.last())
        assertEquals(12_345L, input.backgroundedAt)

        vm.onScreenStart()
        assertEquals(1, input.foregrounded)
    }

    @Test fun `botoes do trackpad pressionam e soltam`() = runTest {
        val vm = vm()
        vm.onButton(NativeBridge.BUTTON_RIGHT, down = true)
        vm.onButton(NativeBridge.BUTTON_RIGHT, down = false)
        assertEquals(listOf(true to NativeBridge.BUTTON_RIGHT, false to NativeBridge.BUTTON_RIGHT), input.buttons)
    }

    @Test fun `volume fisico vira tecla de volume do PC so com a preferencia ligada`() = runTest {
        val vm = vm()
        assertTrue(vm.onVolumeKey(up = true))
        assertEquals(chordEvents(listOf(Vk.VOL_UP), 0), input.keys)

        input.keys.clear()
        vm.setVolumeButtons(false)
        assertFalse(vm.onVolumeKey(up = false))
        assertTrue(input.keys.isEmpty())
    }

    @Test fun `abas seguem a ordem das preferencias e escondem as ocultas`() = runTest {
        prefs.panelOrder = listOf(PanelId.MEDIA, PanelId.KEYBOARD)
        prefs.hidden = setOf(PanelId.POWER)
        val vm = vm()
        assertEquals(
            listOf(PanelId.MEDIA, PanelId.KEYBOARD, PanelId.BROWSER, PanelId.PRESENTATION, PanelId.APPS),
            vm.ui.value.panels,
        )
        assertEquals(PanelId.MEDIA, vm.ui.value.activePanel)

        vm.movePanel(PanelId.BROWSER, -1)
        assertEquals(listOf(PanelId.MEDIA, PanelId.BROWSER, PanelId.KEYBOARD), prefs.panelOrder.take(3))
        vm.setPanelHidden(PanelId.MEDIA, true)
        assertFalse(PanelId.MEDIA in vm.ui.value.panels)
        assertEquals(PanelId.BROWSER, vm.ui.value.activePanel) // the hidden active panel moves on
    }

    @Test fun `mudar a sensibilidade grava nas preferencias`() = runTest {
        val vm = vm()
        vm.setPadConfig(prefs.padConfig.copy(sensitivity = 2.5f, leftHanded = true))
        assertEquals(2.5f, prefs.padConfig.sensitivity)
        assertTrue(vm.ui.value.settings.pad.leftHanded)

        vm.onTouch(PadTouch.Down(TouchSample(0, 100f, 100f, 0)))
        vm.onTouch(PadTouch.Up(TouchSample(0, 100f, 100f, 50)))
        assertEquals(true to NativeBridge.BUTTON_RIGHT, input.buttons.first()) // left-handed tap
    }

    @Test fun `fechar a tela encerra a sessao`() = runTest {
        val vm = vm()
        vm.close()
        assertTrue(input.stopped)
    }

    @Test fun `diferenca do campo oculto vira texto apagar e enter`() {
        assertEquals(listOf<TypingOp>(TypingOp.Text("d")), typingOps("abc", "abcd"))
        assertEquals(listOf<TypingOp>(TypingOp.Key(Vk.BACK)), typingOps("ab", "a"))
        assertEquals(
            listOf(TypingOp.Key(Vk.BACK), TypingOp.Key(Vk.BACK), TypingOp.Text("ando")),
            typingOps("  qu", "  ando"), // autocorrect replaced a word: delete it, type the new one
        )
        assertEquals(
            listOf(TypingOp.Text("oi"), TypingOp.Key(Vk.ENTER), TypingOp.Text("x")),
            typingOps("", "oi\nx"),
        )
        assertEquals(emptyList<TypingOp>(), typingOps("ab", "ab"))
    }
}
