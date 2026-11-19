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
 */package app.duvo.remote.ui.remote

import app.duvo.remote.host.FORBIDDEN_MESSAGE
import app.duvo.remote.host.IMAGE_TOO_LARGE_MESSAGE
import app.duvo.remote.host.MAX_CLIPBOARD_IMAGE_BYTES
import app.duvo.remote.host.DuvoApi
import app.duvo.remote.host.PowerAction
import app.duvo.remote.input.InputState
import app.duvo.remote.input.KeyEvent
import app.duvo.remote.input.RemoteInput
import app.duvo.remote.input.RemotePrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteApiTest {
    private class Input : RemoteInput {
        override val state = MutableStateFlow<InputState>(InputState.Connected)
        override fun sendMouseMove(dx: Int, dy: Int) {}
        override fun sendMouseButton(down: Boolean, button: Int) {}
        override fun sendScroll(amount: Short) {}
        override fun sendHScroll(amount: Short) {}
        override fun sendText(text: String) {}
        override fun sendKey(vk: Short, down: Boolean, modifiers: Byte) {}
        override fun stop() {}
        override fun onBackgrounded(nowMs: Long) {}
        override fun onForegrounded() {}
    }

    /** Records calls; answers from [replies] by path (default 200 + status JSON). */
    private val calls = mutableListOf<String>()
    private val replies = mutableMapOf<String, Pair<Int, String>>()
    private val statusJson = """{"seq":3,"activeApp":"notepad.exe","windowTitle":"Doc","media":{"title":"Song","artist":"Band","app":"x","playing":true},"volume":{"level":40,"muted":false}}"""
    private val api = DuvoApi { m, path, q, _, _, _ ->
        calls += "$m $path ${q.joinToString { "${it.first}=${it.second}" }}"
        // Real long-poll: no change until the 25 s timeout.
        if (path == "duvo/events") kotlinx.coroutines.delay(25_000)
        val r = replies[path] ?: (200 to statusJson)
        r.first to r.second.toByteArray()
    }

    private val dir = File(System.getProperty("java.io.tmpdir"), "duvo-api-${System.nanoTime()}").apply { mkdirs() }
    private val input = Input()

    @After fun tearDown() { dir.deleteRecursively() }

    private fun TestScope.vm() = RemoteViewModel(
        input, RemotePrefs(File(dir, "p.json")), clock = { testScheduler.currentTime }, scope = backgroundScope, api = api,
    )

    private fun TestScope.events(vm: RemoteViewModel) = mutableListOf<UiEvent>().also { l ->
        backgroundScope.launch { vm.events.collect { l += it } }
    }

    @Test fun `status vira linhas da barra`() = runTest {
        val vm = vm()
        runCurrent()
        assertEquals(listOf("notepad \u2014 Doc", "Tocando: Song \u2014 Band", "Volume: 40%"), vm.ui.value.status)
    }

    @Test fun `long-poll para quando sessao sai de Connected`() = runTest {
        val vm = vm()
        runCurrent()
        input.state.value = InputState.Reconnecting(1, 1000)
        runCurrent()
        val n = calls.size
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(n, calls.size)
        vm.close()
    }

    @Test fun `403 vira mensagem de permissao`() = runTest {
        replies["duvo/windows"] = 403 to ""
        val vm = vm()
        val ev = events(vm)
        runCurrent()
        vm.refreshApps()
        runCurrent()
        assertTrue(ev.contains(UiEvent.Message(FORBIDDEN_MESSAGE)))
    }

    @Test fun `janelas e apps no formato do host`() = runTest {
        replies["duvo/windows"] = 200 to """{"windows":[{"id":131332,"process":"claude.exe","title":"Claude"}]}"""
        replies["duvo/apps"] = 200 to """{"apps":[{"id":"b","name":"Bloco"},{"id":"a","name":"Arquivos"},{"id":"c","name":"bloco"}]}"""
        val vm = vm()
        runCurrent()
        vm.refreshApps()
        runCurrent()
        val ui = vm.ui.value
        assertEquals(listOf(app.duvo.remote.host.PcWindow(131332, "Claude", "claude.exe")), ui.windows)
        assertEquals(listOf("Arquivos", "Bloco"), ui.apps.map { it.name })
        vm.close()
    }

    @Test fun `acesso do PC aparece no estado e traduz o preset`() = runTest {
        replies["duvo/access"] = 200 to """{"perm":${0x03000000}}"""
        val vm = vm()
        runCurrent()
        val acc = vm.ui.value.access!!
        assertEquals("Só assistir", acc.presetName)
        assertTrue(!acc.input && !acc.windows && !acc.power && acc.view)
        vm.close()
    }

    @Test fun `presets do host batem com os bits`() {
        assertEquals("Controle", app.duvo.remote.host.AccessInfo(0x07631F00).presetName)
        assertEquals("Acesso total", app.duvo.remote.host.AccessInfo(0x077F1F00).presetName)
        assertEquals("Personalizado", app.duvo.remote.host.AccessInfo(0x07631F01).presetName)
        assertTrue(app.duvo.remote.host.AccessInfo(0x07631F00).let { it.windows && it.power && it.input })
    }

    @Test fun `activate 409 vira mensagem de janela`() = runTest {
        replies["duvo/windows/activate"] = 409 to ""
        val vm = vm()
        val ev = events(vm)
        runCurrent()
        vm.activateWindow(app.duvo.remote.host.PcWindow(5, "x", "y.exe"))
        runCurrent()
        assertTrue(ev.contains(UiEvent.Message(app.duvo.remote.host.DENIED_MESSAGE)))
    }

    @Test fun `energia nao chama api sem confirmar`() = runTest {
        val vm = vm()
        val ev = events(vm)
        runCurrent()
        vm.requestPower(PowerAction.LOCK)
        runCurrent()
        assertTrue(calls.none { it.contains("duvo/power") })
        assertTrue(ev.contains(UiEvent.ConfirmPower(PowerAction.LOCK)))
        vm.confirmPower(PowerAction.LOCK)
        runCurrent()
        assertTrue(calls.any { it == "POST duvo/power action=lock" })
    }

    @Test fun `clipboard vazio nao envia`() = runTest {
        val vm = vm()
        runCurrent()
        vm.sendClipboard("", null)
        vm.sendClipboard(null, ByteArray(0))
        runCurrent()
        assertTrue(calls.none { it.contains("clipboard") })
    }

    @Test fun `imagem acima de 10MB recusada no app`() = runTest {
        val vm = vm()
        val ev = events(vm)
        runCurrent()
        vm.sendClipboard(null, ByteArray(MAX_CLIPBOARD_IMAGE_BYTES + 1))
        runCurrent()
        assertTrue(calls.none { it.contains("clipboard") })
        assertTrue(ev.contains(UiEvent.Message(IMAGE_TOO_LARGE_MESSAGE)))
    }
}
