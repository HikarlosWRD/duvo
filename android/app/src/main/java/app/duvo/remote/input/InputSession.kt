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

import app.duvo.remote.host.CertChangedException
import app.duvo.remote.host.HostStatusException
import app.duvo.remote.host.IDENTITY_UNAVAILABLE_MESSAGE
import app.duvo.remote.host.IdentityUnavailableException
import app.duvo.remote.host.InputOnlyDisabledException
import app.duvo.remote.host.LaunchResult
import app.duvo.remote.host.NotPairedException
import app.duvo.remote.stream.NativeBridge
import app.duvo.remote.stream.StreamCallbacks
import app.duvo.remote.stream.StreamConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/** What [InputSession] needs from the host: one input-only /launch per (re)connection. */
interface InputHost {
    suspend fun launchInputOnly(w: Int, h: Int, fps: Int = StreamConfig.INPUT_ONLY_FPS): LaunchResult
}

/** What the remote-control screen drives: [InputSession] in the app, a fake in tests. */
interface RemoteInput {
    val state: StateFlow<InputState>
    fun sendMouseMove(dx: Int, dy: Int)
    fun sendMouseButton(down: Boolean, button: Int)
    fun sendScroll(amount: Short)
    fun sendHScroll(amount: Short)
    fun sendText(text: String)
    fun sendKey(vk: Short, down: Boolean, modifiers: Byte)
    fun onBackgrounded(nowMs: Long)
    fun onForegrounded()
    fun stop()
}

sealed interface InputState {
    /** First connection in progress. */
    data object Connecting : InputState
    data object Connected : InputState

    /** Connection lost (or never made): reconnection [attempt] runs in [nextInMs] (0 = trying now). */
    data class Reconnecting(val attempt: Int, val nextInMs: Long) : InputState
    data class Ended(val reason: String) : InputState
}

/**
 * Remote-control session over Apollo's input-only mode: moonlight-common-c's control/input channels
 * through [bridge] (the same MoonlightBridge as streaming) with no video decoding — video callbacks
 * accept any format and discard every frame. When the connection drops (PC restarted, Wi-Fi lost) it
 * relaunches and reconnects on its own with [Backoff], until [stop], a failure retrying cannot fix
 * (not paired, certificate changed, permission denied) or [BACKGROUND_LIMIT_MS] in background.
 *
 * [clock] is in milliseconds and must keep counting while the device sleeps (elapsedRealtime):
 * [onForegrounded] checks it because coroutine delays may not fire during deep sleep.
 */
class InputSession(
    private val bridge: NativeBridge,
    private val client: InputHost,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val screenWidth: Int = 1920,
    private val screenHeight: Int = 1080,
    /** Where blocking bridge calls run (LiStartConnection/LiStopConnection). */
    private val io: CoroutineContext = Dispatchers.IO,
    private val log: (String) -> Unit = {},
) : RemoteInput {
    private val _state = MutableStateFlow<InputState>(InputState.Connecting)
    override val state: StateFlow<InputState> = _state.asStateFlow()

    private val lock = Any()
    private var loop: Job? = null
    private var backgroundTimer: Job? = null
    private var backgroundSince: Long? = null

    /** Terminations reported by moonlight-common-c, tagged with the connection they belong to. */
    private val terminations = Channel<Connection>(Channel.UNLIMITED)

    private val ended: Boolean get() = _state.value is InputState.Ended

    /** Starts connecting (once). */
    fun start() {
        synchronized(lock) {
            if (loop != null || ended) return
            loop = scope.launch { run() }
        }
    }

    /** The user left the remote control. */
    override fun stop() = end(ENDED_BY_USER)

    override fun sendMouseMove(dx: Int, dy: Int) {
        if (_state.value != InputState.Connected) return
        bridge.sendMouseMove(dx.toShortClamped(), dy.toShortClamped())
    }

    override fun sendMouseButton(down: Boolean, button: Int) {
        if (_state.value != InputState.Connected) return
        bridge.sendMouseButton(down, button)
    }

    fun click(button: Int = NativeBridge.BUTTON_LEFT) {
        if (_state.value != InputState.Connected) return
        bridge.sendMouseButton(true, button)
        bridge.sendMouseButton(false, button)
    }

    fun sendKeyboard(keyCode: Short, down: Boolean, modifiers: Byte) {
        if (_state.value != InputState.Connected) return
        bridge.sendKeyboard(keyCode, down, modifiers)
    }

    override fun sendScroll(amount: Short) {
        if (_state.value != InputState.Connected) return
        bridge.sendScroll(amount)
    }

    override fun sendHScroll(amount: Short) {
        if (_state.value != InputState.Connected) return
        bridge.sendHScroll(amount)
    }

    override fun sendText(text: String) {
        if (_state.value != InputState.Connected) return
        bridge.sendText(text)
    }

    override fun sendKey(vk: Short, down: Boolean, modifiers: Byte) {
        if (_state.value != InputState.Connected) return
        bridge.sendKey(vk, down, modifiers, Vk.flagsFor(vk))
    }

    /** The app left the screen at [nowMs] (on [clock]'s scale): the session ends after [BACKGROUND_LIMIT_MS]. */
    override fun onBackgrounded(nowMs: Long) {
        synchronized(lock) {
            if (ended) return
            backgroundSince = nowMs
            scheduleBackgroundCheck(nowMs)
        }
    }

    override fun onForegrounded() {
        val since = synchronized(lock) {
            backgroundTimer?.cancel()
            backgroundTimer = null
            backgroundSince.also { backgroundSince = null }
        } ?: return
        if (clock() - since >= BACKGROUND_LIMIT_MS) end(ENDED_IN_BACKGROUND)
    }

    private fun scheduleBackgroundCheck(since: Long) {
        backgroundTimer?.cancel()
        backgroundTimer = scope.launch {
            delay((BACKGROUND_LIMIT_MS - (clock() - since)).coerceAtLeast(0))
            val expired = synchronized(lock) {
                if (backgroundSince != since) return@launch // foregrounded (or backgrounded again) meanwhile
                if (clock() - since >= BACKGROUND_LIMIT_MS) {
                    true
                } else {
                    scheduleBackgroundCheck(since) // the clock lags the delay: wait for the rest
                    false
                }
            }
            if (expired) end(ENDED_IN_BACKGROUND)
        }
    }

    /** First end wins; stops whatever connection is up. */
    private fun end(reason: String) {
        while (true) {
            val cur = _state.value
            if (cur is InputState.Ended) return
            if (_state.compareAndSet(cur, InputState.Ended(reason))) break
        }
        log("ended: $reason")
        synchronized(lock) {
            loop?.cancel()
            backgroundTimer?.cancel()
            backgroundSince = null
        }
        // ATOMIC: runs even when [scope] is already cancelled (e.g. stop() from ViewModel.onCleared).
        // MoonlightBridge.stop() also interrupts a LiStartConnection still in progress.
        scope.launch(io, start = CoroutineStart.ATOMIC) { runCatching { bridge.stop() } }
    }

    private fun setUnlessEnded(s: InputState) {
        while (true) {
            val cur = _state.value
            if (cur is InputState.Ended) return
            if (_state.compareAndSet(cur, s)) return
        }
    }

    private sealed interface Outcome {
        class Connected(val connection: Connection) : Outcome
        data object Retry : Outcome
        class Fatal(val reason: String) : Outcome
    }

    private suspend fun run() {
        var attempt = 0
        while (true) {
            when (val o = connectOnce()) {
                is Outcome.Fatal -> {
                    end(o.reason)
                    return
                }
                is Outcome.Connected -> {
                    attempt = 0
                    setUnlessEnded(InputState.Connected)
                    // Late terminations of earlier connections are still queued: skip them.
                    while (terminations.receive() !== o.connection) Unit
                    withContext(io) { runCatching { bridge.stop() } }
                }
                Outcome.Retry -> Unit
            }
            attempt++
            val wait = Backoff.delayMs(attempt)
            log("reconnecting: attempt $attempt in $wait ms")
            setUnlessEnded(InputState.Reconnecting(attempt, wait))
            delay(wait)
            setUnlessEnded(InputState.Reconnecting(attempt, 0))
        }
    }

    private suspend fun connectOnce(): Outcome {
        val ok = try {
            when (val r = client.launchInputOnly(screenWidth, screenHeight)) {
                is LaunchResult.Failed -> {
                    log("launch failed: ${r.reason}")
                    return Outcome.Retry
                }
                is LaunchResult.Ok -> r
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("launch error: $e")
            return fatalReason(e)?.let(Outcome::Fatal) ?: Outcome.Retry
        }
        val rtspUrl = ok.sessionUrl ?: run {
            log("launch without sessionUrl0")
            return Outcome.Retry
        }
        val cfg = StreamConfig.inputOnly(
            host = ok.addr,
            width = screenWidth,
            height = screenHeight,
            riKey = ok.riKey,
            riKeyId = ok.riKeyId,
            rtspUrl = rtspUrl,
            appVersion = ok.appVersion,
            serverCodecModeSupport = ok.codecModeSupport,
        )
        val connection = Connection()
        val ret = withContext(io) {
            if (ended) return@withContext ABORTED
            val r = try {
                bridge.start(cfg, connection)
            } catch (e: IllegalStateException) {
                // MoonlightBridge: "a stream is already running". Entering remote mode stops the stream
                // (ConnectionModes), but its teardown runs asynchronously: try again after the backoff.
                log("start: $BRIDGE_BUSY_REASON")
                return@withContext BRIDGE_BUSY
            }
            // end() may have run its bridge.stop() before this start began: undo the start ourselves.
            if (r == 0 && ended) runCatching { bridge.stop() }
            r
        }
        if (ret != 0) {
            log("start failed: $ret ${connection.failure.orEmpty()}")
            return Outcome.Retry
        }
        log("connected to ${cfg.host} (${cfg.rtspUrl})")
        return Outcome.Connected(connection)
    }

    /** Null when retrying may help (host down, network, transient host error). */
    private fun fatalReason(e: Exception): String? = when (e) {
        is CertChangedException -> CERT_CHANGED_REASON
        is NotPairedException -> NOT_PAIRED_REASON
        is IdentityUnavailableException -> IDENTITY_UNAVAILABLE_MESSAGE
        is InputOnlyDisabledException -> INPUT_ONLY_DISABLED_REASON
        is HostStatusException -> if (e.code == 401 || e.code == 403) "$REFUSED_REASON ${e.message}" else null
        is IOException -> null
        else -> "Erro inesperado: ${e.message ?: e.javaClass.simpleName}"
    }

    /** Callbacks of one LiStartConnection: no decoder, no audio output. */
    private inner class Connection : StreamCallbacks {
        @Volatile var failure: String? = null

        override fun stageFailed(stage: String, errorCode: Int) {
            failure = "etapa \"$stage\" (erro $errorCode)"
        }

        override fun connectionStarted() {}

        override fun connectionTerminated(errorCode: Int) {
            log("connection terminated: $errorCode")
            terminations.trySend(this)
        }

        override fun videoSetup(videoFormat: Int, width: Int, height: Int, fps: Int): Int = 0

        override fun videoSubmit(
            data: ByteArray,
            csdLength: Int,
            length: Int,
            frameNumber: Int,
            frameType: Int,
            enqueueTimeUs: Long,
        ): Int = NativeBridge.DR_OK

        // moonlight-common-c aborts the connection when audio init fails: accept and drop.
        override fun audioInit(channelCount: Int, sampleRate: Int, samplesPerFrame: Int): Int = 0
    }

    companion object {
        const val BACKGROUND_LIMIT_MS = 10 * 60_000L

        const val ENDED_BY_USER = "Sessão encerrada."
        const val ENDED_IN_BACKGROUND = "Sessão encerrada após 10 minutos com o Duvo em segundo plano."
        const val NOT_PAIRED_REASON =
            "Este aparelho não está pareado com este PC. Leia o QR code exibido no PC para parear."
        const val CERT_CHANGED_REASON =
            "O certificado deste PC mudou. Isso acontece se o Duvo foi reinstalado no PC — ou pode ser um ataque."
        const val REFUSED_REASON = "O PC recusou o controle remoto deste aparelho."
        const val INPUT_ONLY_DISABLED_REASON = "Ative o modo só input no PC (Configurações do Duvo)"
        const val BRIDGE_BUSY_REASON = "a transmissão de tela ainda está sendo encerrada"

        private const val ABORTED = -1
        private const val BRIDGE_BUSY = -2

        private fun Int.toShortClamped(): Short = coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
