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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** What the overlay shows; rates are over the last [StreamSession.refreshStats] window. */
data class Stats(
    val width: Int,
    val height: Int,
    val fps: Float = 0f,
    val decodeMs: Float = 0f,
    /** Frames the network lost (gaps in the frame numbers), in percent. */
    val networkLossPct: Float = 0f,
    /** Estimated network round trip (ms), -1 = unknown. */
    val rttMs: Int = -1,
    /** Host capture+encode time of the latest frame (ms), 0 = not reported. */
    val hostMs: Float = 0f,
    val codecName: String? = null,
    val poorConnection: Boolean = false,
    /** Shown to the user, e.g. when the requested resolution was lowered to fit the decoder. */
    val warning: String? = null,
)

/** Host-to-pad requests forwarded by [StreamSession]. */
interface ControllerFeedback {
    fun rumble(controller: Int, lowFreq: Int, highFreq: Int)
    fun setMotionEventState(controller: Int, motionType: Int, rateHz: Int)
}

/**
 * "Controller only" gaming: an input-only session whose single dummy frame is accepted and dropped,
 * so nothing is decoded and no surface is needed.
 */
class DiscardVideoDecoder : VideoDecoder {
    override fun capsFor(mime: String) = DecoderCaps(mime, "none", true, 8192, 8192, 240.0, true)
    override fun setup(mime: String, width: Int, height: Int, fps: Int) = true
    override fun submit(data: ByteArray, csdLength: Int, length: Int, keyFrame: Boolean) = NativeBridge.DR_OK
    override fun takeStats() = DecoderStats(0, 0f)
    override val codecName: String? = null
    override fun release() {}
}

/** MoonlightBridge refused a second connection: the remote control (or a previous stream) is still running. */
const val BRIDGE_BUSY_MESSAGE =
    "Outra conexão com o PC (controle remoto) ainda está sendo encerrada. Volte e tente de novo em instantes."

enum class EndReason { USER, NETWORK_LOST, HOST_CLOSED, DECODER_UNSUPPORTED }

sealed interface StreamState {
    data object Connecting : StreamState
    data class Streaming(val stats: Stats) : StreamState
    data class Ended(val reason: EndReason, val detail: String? = null) : StreamState
}

/**
 * One desktop stream: negotiates the mode against the decoder, runs the moonlight-common-c
 * connection through [bridge], feeds frames to [decoder] and audio to [audio], and tracks stats.
 */
class StreamSession(
    private val bridge: NativeBridge,
    private val decoder: VideoDecoder,
    private val audio: AudioSink? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::nanoTime,
    /** Rumble/motion requests from the host (gaming); called on moonlight-common-c threads. */
    @Volatile var feedback: ControllerFeedback? = null,
) {
    fun detachSurface() = decoder.detachSurface()
    fun attachSurface(surface: android.view.Surface) = decoder.attachSurface(surface)

    /** Raw input to the host; only meaningful while [state] is Streaming. */
    val input: NativeBridge get() = bridge
    val isStreaming: Boolean get() = _state.value is StreamState.Streaming

    private val _state = MutableStateFlow<StreamState>(StreamState.Connecting)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    /** The configuration actually streamed (after fitting it to the decoder). */
    @Volatile var config: StreamConfig? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val tornDown = AtomicBoolean(false)

    @Volatile private var warning: String? = null
    @Volatile private var failure: String? = null
    @Volatile private var poor = false

    // Frame-number gap accounting, reset at each refreshStats().
    private val lossLock = Any()
    private var lastFrame = 0
    private var framesSeen = 0
    private var framesLost = 0
    private var windowStart = 0L

    suspend fun start(requested: StreamConfig) {
        val caps = decoder.capsFor(requested.mime)
        if (caps == null) {
            end(EndReason.DECODER_UNSUPPORTED, "Este aparelho não decodifica ${requested.mime}")
            return
        }
        val cfg = fit(requested, caps)
        config = cfg
        windowStart = clock()
        val ret = try {
            withContext(io) { bridge.start(cfg, callbacks) }
        } catch (e: IllegalStateException) { // "a stream is already running"
            end(EndReason.NETWORK_LOST, BRIDGE_BUSY_MESSAGE)
            teardown(stopBridge = false)
            return
        }
        if (ret != 0) {
            end(EndReason.NETWORK_LOST, failure ?: "Não foi possível iniciar a transmissão (erro $ret)")
            teardown(stopBridge = false)
        }
    }

    /** User ended the stream (back gesture/button). */
    fun stop() {
        end(EndReason.USER)
        scope.launch { teardown(stopBridge = true) }
    }

    fun sendMouseMove(dx: Int, dy: Int) {
        if (_state.value !is StreamState.Streaming) return
        bridge.sendMouseMove(dx.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
            dy.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
    }

    fun click(button: Int = NativeBridge.BUTTON_LEFT) {
        if (_state.value !is StreamState.Streaming) return
        bridge.sendMouseButton(true, button)
        bridge.sendMouseButton(false, button)
    }

    /** Recomputes the overlay numbers over the time since the previous call (call about once a second). */
    fun refreshStats() {
        val cur = _state.value as? StreamState.Streaming ?: return
        val now = clock()
        val (seen, lost, start) = synchronized(lossLock) {
            val r = Triple(framesSeen, framesLost, windowStart)
            framesSeen = 0
            framesLost = 0
            windowStart = now
            r
        }
        val d = decoder.takeStats()
        val seconds = (now - start) / 1e9f
        val total = seen + lost
        _state.update {
            if (it !is StreamState.Streaming) return@update it
            StreamState.Streaming(
                cur.stats.copy(
                    fps = if (seconds > 0f) d.framesRendered / seconds else 0f,
                    decodeMs = d.avgDecodeMs,
                    networkLossPct = if (total == 0) 0f else lost * 100f / total,
                    rttMs = bridge.rttMs(),
                    hostMs = bridge.lastHostLatencyTenthsMs() / 10f,
                    codecName = decoder.codecName,
                    poorConnection = poor,
                    warning = warning,
                ),
            )
        }
    }

    private fun fit(requested: StreamConfig, caps: DecoderCaps): StreamConfig {
        val w = requested.width
        val h = requested.height
        if (w <= caps.maxWidth && h <= caps.maxHeight && caps.sizeSupported(w, h)) return requested
        val rec = DecoderProbe.recommend(listOf(caps), w, h, requested.fps.toFloat())
        warning = "Resolução ajustada para ${rec.width}x${rec.height} (limite do decoder ${caps.codecName})"
        return requested.copy(width = rec.width, height = rec.height, fps = minOf(requested.fps, rec.fps))
    }

    /** First end wins; later events (e.g. a terminate after the user stopped) are ignored. */
    private fun end(reason: EndReason, detail: String? = null): Boolean {
        while (true) {
            val cur = _state.value
            if (cur is StreamState.Ended) return false
            if (_state.compareAndSet(cur, StreamState.Ended(reason, detail))) return true
        }
    }

    private fun teardown(stopBridge: Boolean) {
        if (!tornDown.compareAndSet(false, true)) return
        // The bridge first: LiStopConnection joins the threads that call into the decoder and audio.
        if (stopBridge) runCatching { bridge.stop() }
        decoder.release()
        audio?.release()
    }

    init {
        decoder.setErrorListener { message ->
            // A codec in the error state takes no more input: the stream would freeze on endless IDR requests.
            if (end(EndReason.DECODER_UNSUPPORTED, message)) scope.launch { teardown(stopBridge = true) }
        }
    }

    private val callbacks = object : StreamCallbacks {
        override fun stageFailed(stage: String, errorCode: Int) {
            failure = "Falha ao conectar ao PC na etapa \"$stage\" (erro $errorCode)"
        }

        override fun connectionStarted() {
            val cfg = config ?: return
            _state.update {
                if (it is StreamState.Ended) it
                else StreamState.Streaming(Stats(cfg.width, cfg.height, codecName = decoder.codecName, warning = warning))
            }
        }

        override fun connectionTerminated(errorCode: Int) {
            val reason = when (errorCode) {
                NativeBridge.ML_ERROR_GRACEFUL_TERMINATION,
                NativeBridge.ML_ERROR_PROTECTED_CONTENT,
                NativeBridge.ML_ERROR_FRAME_CONVERSION,
                -> EndReason.HOST_CLOSED
                else -> EndReason.NETWORK_LOST
            }
            if (end(reason, "código $errorCode")) {
                // Not on this (moonlight-common-c) thread: LiStopConnection joins it.
                scope.launch { teardown(stopBridge = true) }
            }
        }

        override fun connectionStatusUpdate(status: Int) {
            poor = status == NativeBridge.CONN_STATUS_POOR
        }

        override fun videoSetup(videoFormat: Int, width: Int, height: Int, fps: Int): Int {
            if (decoder.setup(NativeBridge.mimeFor(videoFormat), width, height, fps)) return 0
            end(EndReason.DECODER_UNSUPPORTED, "O decoder recusou ${width}x$height@$fps")
            return -1
        }

        override fun videoCleanup() {
            decoder.release()
        }

        override fun videoSubmit(
            data: ByteArray,
            csdLength: Int,
            length: Int,
            frameNumber: Int,
            frameType: Int,
            enqueueTimeUs: Long,
        ): Int {
            synchronized(lossLock) {
                if (frameNumber != lastFrame) {
                    if (lastFrame != 0 && frameNumber > lastFrame + 1) framesLost += frameNumber - lastFrame - 1
                    framesSeen++
                    lastFrame = frameNumber
                }
            }
            return decoder.submit(data, csdLength, length, frameType == NativeBridge.FRAME_TYPE_IDR)
        }

        override fun audioInit(channelCount: Int, sampleRate: Int, samplesPerFrame: Int): Int {
            // No audio output is not a reason to fail the stream (moonlight-common-c aborts on != 0).
            audio?.init(channelCount, sampleRate, samplesPerFrame)
            return 0
        }

        override fun audioPlay(pcm: ShortArray, sampleCount: Int) {
            audio?.play(pcm, sampleCount)
        }

        override fun rumble(controller: Int, lowFreq: Int, highFreq: Int) {
            feedback?.rumble(controller, lowFreq, highFreq)
        }

        override fun setMotionEventState(controller: Int, motionType: Int, rateHz: Int) {
            feedback?.setMotionEventState(controller, motionType, rateHz)
        }

        override fun audioCleanup() {
            audio?.release()
        }
    }
}
