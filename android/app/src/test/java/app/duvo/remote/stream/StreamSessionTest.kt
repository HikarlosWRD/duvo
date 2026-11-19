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

package app.duvo.remote.stream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSessionTest {

    private class FakeBridge : NativeBridge {
        var started: StreamConfig? = null
        var cb: StreamCallbacks? = null
        var stops = 0
        var busy = false

        override fun start(cfg: StreamConfig, cb: StreamCallbacks): Int {
            check(!busy) { "a stream is already running" }
            started = cfg
            this.cb = cb
            return 0
        }

        override fun stop() { stops++ }
        override fun sendMouseMove(dx: Short, dy: Short) {}
        override fun sendMouseButton(down: Boolean, button: Int) {}
        override fun sendKeyboard(keyCode: Short, down: Boolean, modifiers: Byte) {}
    }

    private class FakeDecoder(private val maxW: Int = 3840, private val maxH: Int = 2160) : VideoDecoder {
        var released = false
        var fatalListener: ((String) -> Unit)? = null

        override fun setErrorListener(listener: (String) -> Unit) { fatalListener = listener }

        override fun capsFor(mime: String) =
            DecoderCaps(mime, "fake.decoder", true, maxW, maxH, maxFpsAt1080p = 60.0, lowLatency = false)

        override fun setup(mime: String, width: Int, height: Int, fps: Int) = true
        override fun submit(data: ByteArray, csdLength: Int, length: Int, keyFrame: Boolean) = NativeBridge.DR_OK
        override fun takeStats() = DecoderStats(0, 0f)
        override val codecName: String get() = "fake.decoder"
        override fun release() { released = true }
    }

    private fun cfg(w: Int, h: Int) = StreamConfig(
        host = "192.168.0.10",
        width = w,
        height = h,
        fps = 60,
        bitrateKbps = 20000,
        mime = DecoderProbe.MIME_HEVC,
        riKey = ByteArray(16),
        riKeyId = 7,
        rtspUrl = "rtsp://192.168.0.10:41010",
    )

    @Test fun `config acima do decoder cai para recommend e avisa`() = runBlocking {
        val bridge = FakeBridge()
        val session = StreamSession(bridge, FakeDecoder(maxW = 1920, maxH = 1080), io = Dispatchers.Unconfined)

        session.start(cfg(3840, 2160))

        assertEquals(1920, bridge.started!!.width)
        assertEquals(1080, bridge.started!!.height)
        bridge.cb!!.connectionStarted()
        val state = session.state.value as StreamState.Streaming
        assertTrue(state.stats.warning.orEmpty().contains("resolução ajustada", ignoreCase = true))
    }

    @Test fun `perda de rede termina com NETWORK_LOST`() = runBlocking {
        val bridge = FakeBridge()
        val session = StreamSession(bridge, FakeDecoder(), io = Dispatchers.Unconfined)
        session.start(cfg(1920, 1080))
        bridge.cb!!.connectionStarted()

        bridge.cb!!.connectionTerminated(-1)

        val state = session.state.value as StreamState.Ended
        assertEquals(EndReason.NETWORK_LOST, state.reason)
    }

    @Test fun `stop do usuario termina com USER e libera o decoder`() = runBlocking {
        val bridge = FakeBridge()
        val decoder = FakeDecoder()
        val session = StreamSession(bridge, decoder, io = Dispatchers.Unconfined)
        session.start(cfg(1920, 1080))
        bridge.cb!!.connectionStarted()

        session.stop()

        assertEquals(EndReason.USER, (session.state.value as StreamState.Ended).reason)
        assertTrue(decoder.released)
        assertEquals(1, bridge.stops)
    }

    @Test fun `encerramento normal pelo PC termina com HOST_CLOSED`() = runBlocking {
        val bridge = FakeBridge()
        val session = StreamSession(bridge, FakeDecoder(), io = Dispatchers.Unconfined)
        session.start(cfg(1920, 1080))
        bridge.cb!!.connectionStarted()

        bridge.cb!!.connectionTerminated(NativeBridge.ML_ERROR_GRACEFUL_TERMINATION)

        assertEquals(EndReason.HOST_CLOSED, (session.state.value as StreamState.Ended).reason)
    }

    @Test fun `decoder que nao configura termina com DECODER_UNSUPPORTED`() = runBlocking {
        val bridge = FakeBridge()
        val decoder = object : VideoDecoder by FakeDecoder() {
            override fun setup(mime: String, width: Int, height: Int, fps: Int) = false
        }
        val session = StreamSession(bridge, decoder, io = Dispatchers.Unconfined)
        session.start(cfg(1920, 1080))

        assertEquals(-1, bridge.cb!!.videoSetup(NativeBridge.VIDEO_FORMAT_H265, 1920, 1080, 60))

        assertEquals(EndReason.DECODER_UNSUPPORTED, (session.state.value as StreamState.Ended).reason)
    }

    @Test fun `perda de pacotes vem dos saltos no numero do quadro`() = runBlocking {
        val bridge = FakeBridge()
        var now = 0L
        val session = StreamSession(bridge, FakeDecoder(), io = Dispatchers.Unconfined, clock = { now })
        session.start(cfg(1920, 1080))
        bridge.cb!!.connectionStarted()
        val data = ByteArray(8)
        for (n in listOf(1, 2, 3, 5, 6, 7, 8, 9, 10, 11)) { // frame 4 never arrived
            bridge.cb!!.videoSubmit(data, 0, data.size, n, NativeBridge.FRAME_TYPE_PFRAME, 0L)
        }
        now = 1_000_000_000L

        session.refreshStats()

        val stats = (session.state.value as StreamState.Streaming).stats
        assertEquals(1f / 11f * 100f, stats.networkLossPct, 0.01f)
    }

    @Test fun `bitrate padrao segue a resolucao e o fps`() {
        assertEquals(20000, StreamConfig.defaultBitrateKbps(1920, 1080, 60))
        assertEquals(30000, StreamConfig.defaultBitrateKbps(2560, 1440, 60))
        assertEquals(10000, StreamConfig.defaultBitrateKbps(1920, 1080, 30))
        assertEquals(28284, StreamConfig.defaultBitrateKbps(1920, 1080, 120))
    }

    @Test fun `erro fatal do decoder termina com DECODER_UNSUPPORTED e encerra a conexao`() = runBlocking {
        val bridge = FakeBridge()
        val decoder = FakeDecoder()
        val session = StreamSession(bridge, decoder, io = Dispatchers.Unconfined)
        session.start(cfg(1920, 1080))
        bridge.cb!!.connectionStarted()

        decoder.fatalListener!!("CodecException: 0x80001001")

        val state = session.state.value as StreamState.Ended
        assertEquals(EndReason.DECODER_UNSUPPORTED, state.reason)
        assertEquals(1, bridge.stops)
        assertTrue(decoder.released)
    }

    @Test fun `bridge ocupado pelo controle remoto termina com mensagem em vez de quebrar`() = runBlocking {
        val bridge = FakeBridge().apply { busy = true }
        val decoder = FakeDecoder()
        val session = StreamSession(bridge, decoder, io = Dispatchers.Unconfined)

        session.start(cfg(1920, 1080))

        val state = session.state.value as StreamState.Ended
        assertEquals(EndReason.NETWORK_LOST, state.reason)
        assertEquals(BRIDGE_BUSY_MESSAGE, state.detail)
        assertTrue(decoder.released)
    }
}
