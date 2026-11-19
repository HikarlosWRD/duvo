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

import app.duvo.remote.host.HostStatusException
import app.duvo.remote.host.InputOnlyDisabledException
import app.duvo.remote.host.LaunchResult
import app.duvo.remote.host.NotPairedException
import app.duvo.remote.stream.NativeBridge
import app.duvo.remote.stream.StreamCallbacks
import app.duvo.remote.stream.StreamConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class InputSessionTest {

    private class FakeBridge : NativeBridge {
        val starts = mutableListOf<StreamConfig>()
        val callbacks = mutableListOf<StreamCallbacks>()
        val cb: StreamCallbacks get() = callbacks.last()
        var stops = 0
        /** Return codes for the next start() calls (0 when empty). */
        val startResults = ArrayDeque<Int>()
        /** The next start() calls throw like MoonlightBridge while another stream is running. */
        var busyStarts = 0
        val moves = mutableListOf<Pair<Short, Short>>()
        val buttons = mutableListOf<Pair<Boolean, Int>>()
        val keys = mutableListOf<Triple<Short, Boolean, Byte>>()
        val scrolls = mutableListOf<Short>()
        val hScrolls = mutableListOf<Short>()
        val texts = mutableListOf<String>()
        val vkKeys = mutableListOf<Triple<Short, Boolean, Byte>>()

        override fun start(cfg: StreamConfig, cb: StreamCallbacks): Int {
            if (busyStarts > 0) {
                busyStarts--
                throw IllegalStateException("a stream is already running")
            }
            starts += cfg
            callbacks += cb
            val ret = startResults.removeFirstOrNull() ?: 0
            if (ret == 0) cb.connectionStarted() // LiStartConnection calls it before returning 0
            return ret
        }

        val vkFlags = mutableListOf<Byte>()
        override fun stop() { stops++ }
        override fun sendMouseMove(dx: Short, dy: Short) { moves += dx to dy }
        override fun sendMouseButton(down: Boolean, button: Int) { buttons += down to button }
        override fun sendKeyboard(keyCode: Short, down: Boolean, modifiers: Byte) { keys += Triple(keyCode, down, modifiers) }
        override fun sendScroll(amount: Short) { scrolls += amount }
        override fun sendHScroll(amount: Short) { hScrolls += amount }
        override fun sendText(text: String) { texts += text }
        override fun sendKey(vk: Short, down: Boolean, modifiers: Byte, flags: Byte) { vkKeys += Triple(vk, down, modifiers); vkFlags += flags }
    }

    private class FakeHost : InputHost {
        var launches = 0
        val requested = mutableListOf<Triple<Int, Int, Int>>()
        /** Outcomes for the next launches: a Throwable is thrown, a LaunchResult returned (Ok when empty). */
        val outcomes = ArrayDeque<Any>()

        override suspend fun launchInputOnly(w: Int, h: Int, fps: Int): LaunchResult {
            launches++
            requested += Triple(w, h, fps)
            return when (val o = outcomes.removeFirstOrNull()) {
                null -> ok(launches)
                is Throwable -> throw o
                else -> o as LaunchResult
            }
        }

        fun ok(n: Int) = LaunchResult.Ok(
            appId = 0,
            sessionUrl = "rtspenc://192.168.0.10:41010",
            riKey = ByteArray(16) { n.toByte() },
            riKeyId = n,
            addr = "192.168.0.10",
            appVersion = "7.1.431.-1",
            codecModeSupport = 257,
        )
    }

    private fun TestScope.session(
        bridge: NativeBridge,
        host: InputHost,
        clock: () -> Long = { testScheduler.currentTime },
        scope: CoroutineScope = backgroundScope,
    ) = InputSession(bridge, host, clock, scope, screenWidth = 2400, screenHeight = 1080, io = EmptyCoroutineContext)

    @Test fun `conecta com a tela do aparelho a 30 fps e os dados do launch`() = runTest {
        val bridge = FakeBridge()
        val host = FakeHost()
        val s = session(bridge, host)

        assertEquals(InputState.Connecting, s.state.value)
        s.start()
        runCurrent()

        assertEquals(InputState.Connected, s.state.value)
        assertEquals(listOf(Triple(2400, 1080, 30)), host.requested)
        val cfg = bridge.starts.single()
        assertEquals("192.168.0.10", cfg.host)
        assertEquals(2400, cfg.width)
        assertEquals(1080, cfg.height)
        assertEquals(30, cfg.fps)
        assertEquals("rtspenc://192.168.0.10:41010", cfg.rtspUrl)
        assertEquals("7.1.431.-1", cfg.appVersion)
        assertEquals(257, cfg.serverCodecModeSupport) // never the 0 default: it aborts debug builds
        assertEquals(1, cfg.riKeyId)
    }

    @Test fun `queda de rede vai para Reconnecting e reconecta`() = runTest {
        val bridge = FakeBridge()
        val host = FakeHost()
        val s = session(bridge, host)
        s.start()
        runCurrent()
        assertEquals(InputState.Connected, s.state.value)

        bridge.cb.connectionTerminated(-1)
        runCurrent()

        assertEquals(InputState.Reconnecting(1, 1000), s.state.value)
        assertEquals(1, bridge.stops) // LiStopConnection before the next LiStartConnection
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, bridge.starts.size)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(2, bridge.starts.size)
        assertEquals(2, host.launches) // a fresh /launch (new rikey) per connection
        assertEquals(2, bridge.starts.last().riKeyId)
        assertEquals(InputState.Connected, s.state.value)
    }

    @Test fun `falhas seguidas aumentam o backoff e zeram ao conectar`() = runTest {
        val bridge = FakeBridge()
        val host = FakeHost()
        host.outcomes += IOException("host down")
        host.outcomes += IOException("host down")
        val s = session(bridge, host)
        s.start()
        runCurrent()
        assertEquals(InputState.Reconnecting(1, 1000), s.state.value)

        advanceTimeBy(1000)
        runCurrent()
        assertEquals(InputState.Reconnecting(2, 2000), s.state.value)

        advanceTimeBy(2000)
        runCurrent()
        assertEquals(InputState.Connected, s.state.value)

        bridge.cb.connectionTerminated(-1)
        runCurrent()
        assertEquals(InputState.Reconnecting(1, 1000), s.state.value)
    }

    @Test fun `start do bridge falhando agenda nova tentativa`() = runTest {
        val bridge = FakeBridge()
        bridge.startResults += -1
        val s = session(bridge, FakeHost())
        s.start()
        runCurrent()

        assertEquals(InputState.Reconnecting(1, 1000), s.state.value)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(InputState.Connected, s.state.value)
    }

    @Test fun `launch Failed tambem tenta de novo`() = runTest {
        val host = FakeHost()
        host.outcomes += LaunchResult.Failed("O PC não iniciou a sessão")
        val s = session(FakeBridge(), host)
        s.start()
        runCurrent()

        assertEquals(InputState.Reconnecting(1, 1000), s.state.value)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(InputState.Connected, s.state.value)
    }

    @Test fun `nao pareado encerra sem tentar de novo`() = runTest {
        val host = FakeHost()
        host.outcomes += NotPairedException("HOST-1")
        val s = session(FakeBridge(), host)
        s.start()
        runCurrent()

        val st = s.state.value
        assertTrue(st.toString(), st is InputState.Ended)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, host.launches)
    }

    @Test fun `permissao negada pelo PC encerra sem tentar de novo`() = runTest {
        val host = FakeHost()
        host.outcomes += HostStatusException(403, "Permission denied")
        val s = session(FakeBridge(), host)
        s.start()
        runCurrent()

        assertTrue(s.state.value is InputState.Ended)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, host.launches)
    }

    @Test fun `10 min em background encerra`() = runTest {
        val bridge = FakeBridge()
        val s = session(bridge, FakeHost())
        s.start()
        runCurrent()

        s.onBackgrounded(testScheduler.currentTime)
        advanceTimeBy(599_999)
        runCurrent()
        assertEquals(InputState.Connected, s.state.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(InputState.Ended(InputSession.ENDED_IN_BACKGROUND), s.state.value)
        assertEquals(1, bridge.stops)
    }

    @Test fun `voltar apos 10 min de relogio encerra mesmo sem o timer disparar`() = runTest {
        // The process may sleep in background (delay() does not count deep sleep): the clock decides.
        var now = 1_000L
        val bridge = FakeBridge()
        val s = session(bridge, FakeHost(), clock = { now })
        s.start()
        runCurrent()

        s.onBackgrounded(now)
        now += 600_000
        s.onForegrounded()
        runCurrent()

        assertEquals(InputState.Ended(InputSession.ENDED_IN_BACKGROUND), s.state.value)
        assertEquals(1, bridge.stops)
    }

    @Test fun `voltar antes de 5 min mantem a sessao`() = runTest {
        val s = session(FakeBridge(), FakeHost())
        s.start()
        runCurrent()

        s.onBackgrounded(testScheduler.currentTime)
        advanceTimeBy(400_000)
        s.onForegrounded()
        advanceTimeBy(400_000)
        runCurrent()

        assertEquals(InputState.Connected, s.state.value)
    }

    @Test fun `videoSubmit descartado sem decoder`() = runTest {
        val bridge = FakeBridge()
        val s = session(bridge, FakeHost())
        s.start()
        runCurrent()
        val cb = bridge.cb

        // Any format the host picks is accepted: nothing is decoded.
        assertEquals(0, cb.videoSetup(NativeBridge.VIDEO_FORMAT_H264, 2400, 1080, 30))
        assertEquals(0, cb.videoSetup(NativeBridge.VIDEO_FORMAT_H265, 320, 180, 1))
        assertEquals(0, cb.videoSetup(NativeBridge.VIDEO_FORMAT_AV1_MAIN8, 3840, 2160, 120))
        val frame = ByteArray(64) { 0x55 }
        assertEquals(NativeBridge.DR_OK, cb.videoSubmit(frame, 0, frame.size, 1, NativeBridge.FRAME_TYPE_IDR, 0L))
        assertEquals(NativeBridge.DR_OK, cb.videoSubmit(frame, 0, frame.size, 2, NativeBridge.FRAME_TYPE_PFRAME, 0L))
        assertEquals(0, cb.audioInit(2, 48000, 240)) // != 0 would abort the connection
        assertEquals(InputState.Connected, s.state.value)
    }

    @Test fun `input so vai ao bridge quando conectado`() = runTest {
        val bridge = FakeBridge()
        val host = FakeHost()
        host.outcomes += IOException("host down")
        val s = session(bridge, host)
        s.start()
        runCurrent()

        s.sendMouseMove(5, 5) // Reconnecting: dropped
        advanceTimeBy(1000)
        runCurrent()
        s.sendMouseMove(10, -40000)
        s.click()
        s.sendKeyboard(0x41, true, 0)

        assertEquals(listOf(10.toShort() to Short.MIN_VALUE), bridge.moves)
        assertEquals(listOf(true to NativeBridge.BUTTON_LEFT, false to NativeBridge.BUTTON_LEFT), bridge.buttons)
        assertEquals(listOf(Triple(0x41.toShort(), true, 0.toByte())), bridge.keys)
    }

    @Test fun `scroll texto e tecla so vao ao bridge quando conectado`() = runTest {
        val bridge = FakeBridge()
        val host = FakeHost()
        host.outcomes += IOException("host down")
        val s = session(bridge, host)
        s.start()
        runCurrent()

        s.sendScroll(120) // Reconnecting: dropped
        s.sendHScroll(120)
        s.sendText("x")
        s.sendKey(0x41, true, 0)
        advanceTimeBy(1000)
        runCurrent()
        s.sendScroll(120)
        s.sendHScroll(-120)
        s.sendText("ação ç ñ 日本")
        s.sendKey(0x41, true, NativeBridge.MOD_CTRL)

        assertEquals(listOf(120.toShort()), bridge.scrolls)
        assertEquals(listOf((-120).toShort()), bridge.hScrolls)
        assertEquals(listOf("ação ç ñ 日本"), bridge.texts)
        assertEquals(listOf(Triple(0x41.toShort(), true, 0x02.toByte())), bridge.vkKeys)
        assertEquals(listOf<Byte>(0), bridge.vkFlags)
    }

    @Test fun `tecla de midia vai nao normalizada`() = runTest {
        val bridge = FakeBridge()
        val s = session(bridge, FakeHost())
        s.start()
        runCurrent()
        s.sendKey(Vk.VOL_DOWN, true, 0)
        assertEquals(listOf(NativeBridge.FLAG_NON_NORMALIZED), bridge.vkFlags)
    }

    @Test fun `stop encerra e ignora queda posterior`() = runTest {
        val bridge = FakeBridge()
        val host = FakeHost()
        val s = session(bridge, host)
        s.start()
        runCurrent()
        val cb = bridge.cb

        s.stop()
        runCurrent()
        cb.connectionTerminated(-1)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(InputState.Ended(InputSession.ENDED_BY_USER), s.state.value)
        assertEquals(1, bridge.stops)
        assertEquals(1, host.launches)
    }

    @Test fun `callback atrasado da conexao anterior nao derruba a nova`() = runTest {
        val bridge = FakeBridge()
        val s = session(bridge, FakeHost())
        s.start()
        runCurrent()
        val old = bridge.cb
        old.connectionTerminated(-1)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(InputState.Connected, s.state.value)

        old.connectionTerminated(-1) // late duplicate from the previous connection

        runCurrent()
        assertEquals(InputState.Connected, s.state.value)
        assertEquals(2, bridge.starts.size)
    }

    @Test fun `modo so input desligado no PC encerra com aviso em vez de tentar sempre`() = runTest {
        val host = FakeHost()
        host.outcomes += InputOnlyDisabledException()
        val s = session(FakeBridge(), host)
        s.start()
        runCurrent()

        assertEquals(InputState.Ended("Ative o modo só input no PC (Configurações do Duvo)"), s.state.value)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, host.launches)
    }

    @Test fun `bridge ocupado pela transmissao nao derruba o app e tenta de novo`() = runTest {
        val bridge = FakeBridge()
        bridge.busyStarts = 1
        val s = session(bridge, FakeHost())
        s.start()
        runCurrent()

        assertEquals(InputState.Reconnecting(1, 1000), s.state.value)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(InputState.Connected, s.state.value)
    }
}
