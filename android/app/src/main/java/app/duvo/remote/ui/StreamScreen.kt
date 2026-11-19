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
package app.duvo.remote.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Network
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Display
import android.view.InputEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.duvo.remote.DuvoApp
import app.duvo.remote.gamepad.ControllerSender
import app.duvo.remote.gamepad.GamepadState
import app.duvo.remote.gamepad.PhysicalPads
import app.duvo.remote.host.CertChangedException
import app.duvo.remote.host.HostClient
import app.duvo.remote.host.HostStatusException
import app.duvo.remote.host.IDENTITY_UNAVAILABLE_MESSAGE
import app.duvo.remote.host.IdentityUnavailableException
import app.duvo.remote.host.LaunchOptions
import app.duvo.remote.host.LaunchResult
import app.duvo.remote.host.NotPairedException
import app.duvo.remote.input.Vk
import app.duvo.remote.net.ConnectionDiagnostics
import app.duvo.remote.net.Raced
import app.duvo.remote.net.WifiBinding
import app.duvo.remote.net.WifiBindingCore
import app.duvo.remote.net.message
import app.duvo.remote.net.raceWithDiagnosis
import app.duvo.remote.pairing.QrPayload
import app.duvo.remote.stream.AudioTrackSink
import app.duvo.remote.stream.ControllerFeedback
import app.duvo.remote.stream.DecoderProbe
import app.duvo.remote.stream.DiscardVideoDecoder
import app.duvo.remote.stream.DisplayModeInfo
import app.duvo.remote.stream.DisplayModes
import app.duvo.remote.stream.EndReason
import app.duvo.remote.stream.MediaCodecInfoView
import app.duvo.remote.stream.MediaCodecVideoDecoder
import app.duvo.remote.stream.MoonlightBridge
import app.duvo.remote.stream.NativeBridge
import app.duvo.remote.stream.Stats
import app.duvo.remote.stream.StreamConfig
import app.duvo.remote.stream.StreamMode
import app.duvo.remote.stream.StreamResolver
import app.duvo.remote.stream.StreamSession
import app.duvo.remote.stream.StreamState
import app.duvo.remote.stream.TouchMode
import app.duvo.remote.ui.stream.MirrorTouchLayer
import app.duvo.remote.ui.stream.VirtualPad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

const val CERT_CHANGED_MESSAGE =
    "O certificado deste PC mudou. Isso acontece se o Duvo foi reinstalado no PC — ou pode ser um ataque. Parear de novo?"

sealed interface StreamUi {
    data object Launching : StreamUi
    data class Live(val session: StreamSession, val config: StreamConfig) : StreamUi
    /** R11: the pinned host presented another certificate. */
    data class CertChanged(val hostId: String) : StreamUi
    data class Error(val message: String) : StreamUi
}

/** Maps a failure while talking to the host before the stream starts (pt-BR). */
fun streamFailureUi(e: Throwable): StreamUi = when (e) {
    is CertChangedException -> StreamUi.CertChanged(e.hostId)
    is IdentityUnavailableException -> StreamUi.Error(IDENTITY_UNAVAILABLE_MESSAGE)
    is NotPairedException -> StreamUi.Error("Este aparelho não está pareado com este PC. Leia o QR code exibido no PC para parear.")
    is HostStatusException -> StreamUi.Error("O PC recusou iniciar a transmissão. ${e.message}")
    is IOException -> StreamUi.Error("Não foi possível falar com o PC: ${e.message ?: e.javaClass.simpleName}")
    else -> StreamUi.Error("Erro inesperado: ${e.message ?: e.javaClass.simpleName}")
}

fun launchFailedUi(r: LaunchResult.Failed): StreamUi = StreamUi.Error(r.reason)

/** Set by the stream screen while it is shown: controller key/motion events from the activity. */
interface GamepadEventHost {
    var gamepadHandler: ((InputEvent) -> Boolean)?
}

/**
 * One stream screen, in one of three shapes:
 * - [StreamMode.REMOTE]: mirroring of the PC screen with absolute touch (spec §8.2);
 * - [StreamMode.GAMING] with video: virtual display + on-screen pad (§8.3);
 * - [StreamMode.MONITOR]: like REMOTE, but the picture is a virtual display added to Windows as an extra monitor (§8.9);
 * - [StreamMode.GAMING] without video ([ARG_VIDEO] false): input-only session, the phone is just a pad (§8.3.1).
 */
class StreamViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
    private val duvo = app as DuvoApp
    private val hostId: String? = handle.get<String>(ARG_HOST)?.ifEmpty { null }
    private val argAddrs: List<String> = handle.get<String>(ARG_ADDRS).orEmpty().split(',').filter { it.isNotBlank() }
    private val argPort: Int = handle.get<Int>(ARG_PORT) ?: QrPayload.DEFAULT_PORT
    val hostName: String = handle.get<String>(ARG_NAME).orEmpty().ifEmpty { "PC" }
    val mode: StreamMode = runCatching { StreamMode.valueOf(handle.get<String>(ARG_MODE).orEmpty()) }.getOrDefault(StreamMode.GAMING)
    val video: Boolean = handle.get<Boolean>(ARG_VIDEO) ?: true
    val settings = duvo.streamPrefs.get(mode)
    val padHaptics: Boolean get() = duvo.remotePrefs.haptics
    val padConfig get() = duvo.remotePrefs.padConfig

    private val launcher = StreamLaunch(viewModelScope)
    val ui: StateFlow<StreamUi> = launcher.ui

    private var session: StreamSession? = null
    private val sender = ControllerSender(MoonlightBridge)
    private var physical: PhysicalPads? = null

    /** Emits when the controller chord (LB + RB + Start) asks for the in-stream menu. */
    private val _menuChord = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val menuChord: kotlinx.coroutines.flow.SharedFlow<Unit> = _menuChord

    private val _physicalCount = MutableStateFlow(0)
    val physicalCount: StateFlow<Int> = _physicalCount.asStateFlow()

    private val _padVisible = MutableStateFlow(true)
    val padVisible: StateFlow<Boolean> = _padVisible.asStateFlow()

    /** Process bound to Wi-Fi from launch to stop: OkHttp and moonlight-common-c's sockets use it. */
    private var wifi: WifiBindingCore<Network>.Lease? = null

    private val feedback = object : ControllerFeedback {
        override fun rumble(controller: Int, lowFreq: Int, highFreq: Int) {
            if (physical?.rumble(controller, lowFreq, highFreq) == true) return
            if (controller == VIRTUAL_SLOT && padHaptics) phoneRumble(lowFreq, highFreq)
        }

        override fun setMotionEventState(controller: Int, motionType: Int, rateHz: Int) {
            physical?.setMotion(controller, motionType, rateHz)
        }
    }

    private fun phoneRumble(low: Int, high: Int) {
        val v = getApplication<Application>().getSystemService(Vibrator::class.java) ?: return
        val amp = (maxOf(low, high) / 257).coerceIn(0, 255)
        if (amp == 0) v.cancel() else v.vibrate(VibrationEffect.createOneShot(5_000, amp))
    }

    fun handleGamepadEvent(e: InputEvent): Boolean = mode == StreamMode.GAMING && physical?.handle(e) == true

    /** Only shows or hides the on-screen buttons; the pad stays connected to the PC. */
    fun setPadVisible(visible: Boolean) {
        _padVisible.value = visible
    }

    /** Bumped when the layout editor closes so the pad redraws with the saved layout. */
    private val _layoutVersion = MutableStateFlow(0)
    val layoutVersion: StateFlow<Int> = _layoutVersion.asStateFlow()
    fun layoutChanged() { _layoutVersion.value += 1 }

    private var virtualChord = false

    fun onVirtualPad(s: GamepadState) {
        val chord = app.duvo.remote.gamepad.Btn.LB or app.duvo.remote.gamepad.Btn.RB or app.duvo.remote.gamepad.Btn.START
        val now = (s.buttons and chord) == chord
        if (now && !virtualChord) _menuChord.tryEmit(Unit)
        virtualChord = now
        sender.update(VIRTUAL_SLOT, s)
    }

    private fun settingsPadCtype() = duvo.layoutStore.active().style.ctype

    fun begin(
        holder: SurfaceHolder?,
        screenW: Int,
        screenH: Int,
        current: DisplayModeInfo,
        supported: List<DisplayModeInfo>,
    ) {
        if (!launcher.canBegin) return
        duvo.connectionModes.enter(this) { stop() } // stops a remote-control session still running
        val lease = WifiBinding.acquire(duvo).also { wifi = it }
        launcher.begin {
            val ep = hostId?.let { duvo.discovery.endpointFor(it) }
            val addrs = ep?.addresses ?: argAddrs
            val port = ep?.port ?: argPort
            val caps = withContext(Dispatchers.Default) { DecoderProbe.probe(MediaCodecInfoView.listDecoders()) }
            val r = StreamResolver.resolve(settings, caps, screenW, screenH, DisplayModes.maxRefreshHz(current, supported))
            val gaming = mode == StreamMode.GAMING
            val result = try {
                val raced = raceWithDiagnosis(
                    attempt = {
                        val client = HostClient(
                            addrs, port, duvo.certStore,
                            deviceName = Build.MODEL ?: "Android",
                            socketFactory = lease.network?.socketFactory,
                            expectedUniqueId = hostId,
                        )
                        if (video) {
                            client.launchDesktop(
                                r.width, r.height, r.fps,
                                LaunchOptions(r.virtualDisplay, r.hostAudio, controllers = if (gaming) 1 else 0),
                            )
                        } else {
                            client.launchInputOnly(screenW, screenH)
                        }
                    },
                    diagnose = { ConnectionDiagnostics.run(duvo, addrs, port) },
                )
                when (raced) {
                    is Raced.Diagnosed -> {
                        launcher.set(StreamUi.Error(raced.diagnosis.message()))
                        return@begin
                    }
                    is Raced.Completed -> raced.value
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                launcher.set(streamFailureUi(e))
                return@begin
            }
            val ok = when (result) {
                is LaunchResult.Failed -> {
                    launcher.set(launchFailedUi(result))
                    return@begin
                }
                is LaunchResult.Ok -> result
            }
            val cfg = if (video) {
                val m = DisplayModes.modeForFps(current, supported, r.fps)
                StreamConfig(
                    host = ok.addr, width = r.width, height = r.height, fps = r.fps, bitrateKbps = r.bitrateKbps,
                    mime = r.mime, riKey = ok.riKey, riKeyId = ok.riKeyId,
                    rtspUrl = ok.sessionUrl ?: "rtsp://${ok.addr}:${port + RTSP_PORT_OFFSET}",
                    appVersion = ok.appVersion, serverCodecModeSupport = ok.codecModeSupport,
                    refreshRateX100 = (m.refreshHz * 100).roundToInt(),
                )
            } else {
                StreamConfig.inputOnly(
                    ok.addr, screenW, screenH, ok.riKey, ok.riKeyId,
                    ok.sessionUrl ?: "rtsp://${ok.addr}:${port + RTSP_PORT_OFFSET}", ok.appVersion, ok.codecModeSupport,
                )
            }
            val s = try {
                val decoder = if (video && holder != null) MediaCodecVideoDecoder(caps, holder.surface) else DiscardVideoDecoder()
                StreamSession(MoonlightBridge, decoder, if (video && !r.hostAudio) AudioTrackSink() else null)
            } catch (e: Throwable) { // UnsatisfiedLinkError if libmoonlight is missing for this ABI
                launcher.set(StreamUi.Error("Falha ao carregar o módulo de transmissão: ${e.message}"))
                return@begin
            }
            if (gaming) s.feedback = feedback
            session = s
            app.duvo.remote.stream.KeepAliveService.start(duvo)
            Log.i(TAG, "stream $mode video=$video ${cfg.mime} ${cfg.width}x${cfg.height}@${cfg.fps} ${cfg.bitrateKbps} kbps -> ${cfg.host}")
            launch {
                while (isActive) {
                    delay(1000)
                    s.refreshStats()
                }
            }
            if (gaming) launch {
                s.state.first { it is StreamState.Streaming }
                startControllers()
            }
            launch(Dispatchers.IO) { s.start(cfg) }
            launcher.set(StreamUi.Live(s, cfg))
            r.warning?.let { w -> withContext(Dispatchers.Main) { Toast.makeText(duvo, w, Toast.LENGTH_LONG).show() } }
            // Bluetooth and 2.4 GHz Wi-Fi share one radio on most phones: the stream suffers when both are on.
            val btOn = runCatching { android.provider.Settings.Global.getInt(duvo.contentResolver, "bluetooth_on", 0) == 1 }.getOrDefault(false)
            if (btOn && app.duvo.remote.ui.wifiBandLabel(duvo) == "2,4 GHz") {
                withContext(Dispatchers.Main) {
                    Toast.makeText(duvo, "Bluetooth ligado com Wi-Fi 2,4 GHz pode causar perda de rede. Use a rede 5 GHz.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startControllers() {
        val p = PhysicalPads(duvo, sender, MoonlightBridge, onMenuChord = { _menuChord.tryEmit(Unit) }, onChanged = { n ->
            _physicalCount.value = n
            if (n > 0 && _padVisible.value) { // a real controller: hide the on-screen one and free its slot
                _padVisible.value = false
                sender.disconnect(VIRTUAL_SLOT)
            }
        })
        physical = p
        p.start()
        _physicalCount.value = p.count
        if (p.count == 0 && _padVisible.value) sender.connect(VIRTUAL_SLOT, settingsPadCtype(), PAD_CAPS)
        else _padVisible.value = false
    }

    private var awayJob: kotlinx.coroutines.Job? = null

    val hasSession: Boolean get() = session != null && !stopped

    private var stopped = false

    /** The picture surface is gone (minimized or screen off): keep streaming, give up after [AWAY_LIMIT_MS]. */
    fun onSurfaceLost() {
        val s = session ?: return
        if (stopped) return
        s.detachSurface()
        awayJob?.cancel()
        awayJob = viewModelScope.launch {
            delay(AWAY_LIMIT_MS)
            stop()
            launcher.set(StreamUi.Error("A transmissão foi encerrada depois de 10 minutos com o Duvo em segundo plano."))
        }
    }

    fun attachSurface(holder: SurfaceHolder) {
        awayJob?.cancel()
        session?.attachSurface(holder.surface)
    }

    fun stop(endOnHost: Boolean = true) {
        if (!stopped && endOnHost && video && settings.screen == app.duvo.remote.stream.HostScreen.VIRTUAL) {
            // Leaving a virtual-display stream: take the extra monitor away from Windows too.
            val ep = hostId?.let { duvo.discovery.endpointFor(it) }
            val addrs = ep?.addresses ?: argAddrs
            val port = ep?.port ?: argPort
            @Suppress("OPT_IN_USAGE")
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                runCatching { HostClient(addrs, port, duvo.certStore, deviceName = Build.MODEL ?: "Android", expectedUniqueId = hostId).endHostSession() }
            }
        }
        stopped = true
        awayJob?.cancel()
        physical?.stop()
        physical = null
        session?.stop()
        launcher.stop()
        wifi?.release()
        app.duvo.remote.stream.KeepAliveService.stop(duvo)
    }

    /** "Parear de novo": forget the stale pin so the next QR pairs from scratch. */
    fun forgetPin(hostId: String) = duvo.certStore.unpin(hostId)

    override fun onCleared() {
        stop()
        duvo.connectionModes.leave(this)
    }

    companion object {
        const val ARG_ADDRS = "addrs"
        const val ARG_PORT = "port"
        const val ARG_NAME = "name"
        const val ARG_HOST = "host"
        const val ARG_MODE = "mode"
        const val ARG_VIDEO = "video"
        private const val TAG = "DuvoStream"
        private const val RTSP_PORT_OFFSET = 21
        private const val AWAY_LIMIT_MS = 10 * 60_000L
        private const val VIRTUAL_SLOT = 0
        private const val PAD_CAPS = NativeBridge.CCAP_ANALOG_TRIGGERS or NativeBridge.CCAP_RUMBLE
    }
}

@Composable
fun StreamScreen(
    onExit: () -> Unit,
    onPairAgain: () -> Unit,
    onRestart: () -> Unit = {},
    vm: StreamViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var showStats by remember { mutableStateOf(vm.settings.showStats) }
    var keyboard by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    // Controller shortcut: LB + RB + Start toggles the same menu as Back.
    LaunchedEffect(vm) { vm.menuChord.collect { menuOpen = !menuOpen } }

    // Back never leaves the stream by itself: it opens the pause menu (darkened screen); Back again closes it.
    BackHandler {
        when {
            editing -> { editing = false; vm.layoutChanged() }
            menuOpen -> menuOpen = false
            else -> menuOpen = true
        }
    }

    // Controllers attached to the phone: the activity forwards their events while this screen is up.
    DisposableEffect(activity) {
        val host = activity as? GamepadEventHost
        host?.gamepadHandler = { e -> vm.handleGamepadEvent(e) }
        onDispose { host?.gamepadHandler = null }
    }

    // Full screen, landscape, screen on while streaming; restored on leave.
    DisposableEffect(activity) {
        val window = activity?.window
        val oldOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.let { w -> w.attributes = w.attributes.apply { preferredDisplayModeId = 0 } }
            if (oldOrientation != null) activity.requestedOrientation = oldOrientation
        }
    }

    // R12(b): switch the panel to the mode whose refresh rate matches the stream fps.
    val live = ui as? StreamUi.Live
    LaunchedEffect(live?.config?.fps) {
        val fps = live?.config?.fps ?: return@LaunchedEffect
        if (!vm.video) return@LaunchedEffect
        val window = activity?.window ?: return@LaunchedEffect
        val display = currentDisplay(context) ?: return@LaunchedEffect
        val mode = DisplayModes.modeForFps(DisplayModes.current(display), DisplayModes.supported(display), fps)
        window.attributes = window.attributes.apply { preferredDisplayModeId = mode.id }
    }

    // Without video there is no surface to wait for: start right away.
    if (!vm.video) {
        LaunchedEffect(Unit) {
            val metrics = context.resources.displayMetrics
            val display = currentDisplay(context)
            val current = display?.let(DisplayModes::current) ?: DisplayModeInfo(0, metrics.widthPixels, metrics.heightPixels, 60f)
            vm.begin(null, maxOf(metrics.widthPixels, metrics.heightPixels), minOf(metrics.widthPixels, metrics.heightPixels), current, listOf(current))
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (vm.video) {
            val aspect = live?.config?.let { it.width.toFloat() / it.height }
            val surfaceModifier = if (aspect != null) Modifier.align(Alignment.Center).aspectRatio(aspect) else Modifier.fillMaxSize()
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {
                                if (vm.hasSession) { vm.attachSurface(h); return }
                                val metrics = ctx.resources.displayMetrics
                                val display = currentDisplay(ctx)
                                val current = display?.let(DisplayModes::current)
                                    ?: DisplayModeInfo(0, metrics.widthPixels, metrics.heightPixels, 60f)
                                val supported = display?.let(DisplayModes::supported) ?: listOf(current)
                                // Streaming is landscape: the wider side is the width.
                                vm.begin(
                                    h,
                                    maxOf(metrics.widthPixels, metrics.heightPixels),
                                    minOf(metrics.widthPixels, metrics.heightPixels),
                                    current,
                                    supported,
                                )
                            }

                            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}

                            override fun surfaceDestroyed(h: SurfaceHolder) {
                                vm.onSurfaceLost() // the decoder is rebuilt when the surface returns
                            }
                        })
                    }
                },
                modifier = surfaceModifier,
            )
        }

        when (val s = ui) {
            StreamUi.Launching -> Centered {
                CircularProgressIndicator()
                Text("Conectando a ${vm.hostName}…", color = Color.White)
            }
            is StreamUi.Live -> {
                LiveLayer(
                    vm, s, showStats, onToggleStats = { showStats = !showStats },
                    onExit = { vm.stop(); onExit() },
                    keyboard = keyboard,
                    onKeyboardClose = { keyboard = false },
                )
                if (menuOpen) {
                    app.duvo.remote.ui.stream.GamingMenu(
                        vm.mode, vm.video,
                        onClose = { menuOpen = false },
                        onApply = { menuOpen = false; vm.stop(endOnHost = false); onRestart() },
                        actions = {
                            StreamActions(
                                vm, s.session.input, showStats,
                                onToggleStats = { showStats = !showStats },
                                onExit = { vm.stop(); onExit() },
                                onEditLayout = { menuOpen = false; editing = true },
                                onClose = { menuOpen = false },
                                onKeyboard = { menuOpen = false; keyboard = !keyboard },
                            )
                        },
                    )
                }
                if (editing) {
                    app.duvo.remote.ui.stream.PadEditorScreen(onBack = { editing = false; vm.layoutChanged() })
                }
            }
            is StreamUi.Error -> Centered {
                Text(s.message, color = Color.White, textAlign = TextAlign.Center)
                Button(onClick = onExit) { Text("Voltar") }
            }
            is StreamUi.CertChanged -> AlertDialog(
                onDismissRequest = onExit,
                title = { Text("Certificado alterado") },
                text = { Text(CERT_CHANGED_MESSAGE) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.forgetPin(s.hostId)
                        onPairAgain()
                    }) { Text("Parear de novo") }
                },
                dismissButton = { TextButton(onClick = onExit) { Text("Cancelar") } },
            )
        }
    }
}

@Composable
private fun LiveLayer(
    vm: StreamViewModel,
    live: StreamUi.Live,
    showStats: Boolean,
    onToggleStats: () -> Unit,
    onExit: () -> Unit,
    keyboard: Boolean,
    onKeyboardClose: () -> Unit,
) {
    val state by live.session.state.collectAsState()
    val cfg = live.config
    val context = LocalContext.current
    var touchMode by remember { mutableStateOf(vm.settings.touchMode) }

    when (val st = state) {
        StreamState.Connecting -> Centered {
            CircularProgressIndicator()
            Text("Iniciando a transmissão…", color = Color.White)
        }
        is StreamState.Streaming -> Box(Modifier.fillMaxSize()) {
            if (vm.mode != StreamMode.GAMING) {
                MirrorTouchLayer(
                    input = live.session.input,
                    videoW = cfg.width,
                    videoH = cfg.height,
                    touchMode = touchMode,
                    padCfg = vm.padConfig,
                    onNativeUnsupported = {
                        if (touchMode == TouchMode.NATIVE) {
                            touchMode = TouchMode.MOUSE
                            Toast.makeText(context, "O PC não aceitou toque nativo; usando o modo mouse.", Toast.LENGTH_LONG).show()
                        }
                    },
                )
            } else {
                val visible by vm.padVisible.collectAsState()
                if (!vm.video) {
                    val n by vm.physicalCount.collectAsState()
                    Text(
                        "Modo só controle: olhe para a tela do PC." + if (n > 0) "\nControle físico conectado ($n)." else "",
                        color = Color(0x99FFFFFF),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                val duvoApp = context.applicationContext as DuvoApp
                val version by vm.layoutVersion.collectAsState()
                val opacity by duvoApp.uiPrefs.padOpacity.collectAsState()
                val through by duvoApp.uiPrefs.touchThrough.collectAsState()
                val free = if (vm.video && through) {
                    app.duvo.remote.ui.stream.FreeTouch(live.session.input, cfg.width, cfg.height, vm.padConfig)
                } else null
                if (visible) {
                    val layout = remember(version) { duvoApp.layoutStore.active() }
                    VirtualPad(layout, vm.padHaptics, onState = vm::onVirtualPad, opacityScale = opacity, freeTouch = free)
                } else if (vm.video) {
                    // Buttons hidden: the whole screen is the PC (mouse), the pad stays connected underneath.
                    MirrorTouchLayer(live.session.input, cfg.width, cfg.height, TouchMode.MOUSE, vm.padConfig, onNativeUnsupported = {})
                }
            }
            if (showStats) {
                Text(
                    overlayText(cfg, st.stats),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .background(Color(0x99000000))
                        .padding(6.dp),
                )
            }
            if (keyboard) HiddenKeyboard(live.session.input, onClose = onKeyboardClose)
        }
        is StreamState.Ended -> Centered {
            Text(endMessage(st), color = Color.White, textAlign = TextAlign.Center)
            Button(onClick = onExit) { Text("Voltar") }
        }
    }
}

/** The stream actions listed at the top of the in-stream menu, depending on the mode. */
@Composable
private fun StreamActions(
    vm: StreamViewModel,
    input: NativeBridge,
    showStats: Boolean,
    onToggleStats: () -> Unit,
    onExit: () -> Unit,
    onEditLayout: () -> Unit,
    onClose: () -> Unit,
    onKeyboard: () -> Unit,
) {
    val visible by vm.padVisible.collectAsState()
    app.duvo.remote.ui.theme.SectionLabel("Ações")
    app.duvo.remote.ui.theme.SettingsGroup {
        app.duvo.remote.ui.theme.SettingsRow("Continuar", onClick = onClose)
        app.duvo.remote.ui.theme.GroupDivider()
        if (vm.mode != StreamMode.GAMING) {
            app.duvo.remote.ui.theme.SettingsRow("Teclado", onClick = onKeyboard)
            app.duvo.remote.ui.theme.GroupDivider()
            app.duvo.remote.ui.theme.SettingsRow("Esc", onClick = { tap(input, Vk.ESC); onClose() })
            app.duvo.remote.ui.theme.GroupDivider()
            app.duvo.remote.ui.theme.SettingsRow("Tecla Windows", onClick = { tap(input, Vk.LWIN); onClose() })
            app.duvo.remote.ui.theme.GroupDivider()
        } else {
            // Hiding only makes sense with the PC picture behind; in controller-only mode there is nothing to see.
            if (vm.video) {
                app.duvo.remote.ui.theme.SettingsRow(if (visible) "Ocultar botões" else "Mostrar botões", onClick = { vm.setPadVisible(!visible); onClose() })
                app.duvo.remote.ui.theme.GroupDivider()
            }
            app.duvo.remote.ui.theme.SettingsRow("Editar layout dos botões", onClick = onEditLayout)
            app.duvo.remote.ui.theme.GroupDivider()
        }
        if (vm.video) {
            app.duvo.remote.ui.theme.SettingsRow(if (showStats) "Ocultar estatísticas" else "Mostrar estatísticas", onClick = { onToggleStats(); onClose() })
            app.duvo.remote.ui.theme.GroupDivider()
        }
        app.duvo.remote.ui.theme.SettingsRow("Sair da transmissão", onClick = onExit)
    }
}

private fun tap(input: NativeBridge, vk: Short) {
    input.sendKey(vk, true, 0)
    input.sendKey(vk, false, 0)
}

/**
 * Soft keyboard for mirroring: an invisible field that always holds one sentinel space. Typed text
 * is sent as Unicode; deleting the sentinel sends Backspace; the IME action sends Enter.
 */
@Composable
private fun HiddenKeyboard(input: NativeBridge, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    var value by remember { mutableStateOf(TextFieldValue(" ", TextRange(1))) }
    BasicTextField(
        value = value,
        onValueChange = { v ->
            when {
                v.text.isEmpty() -> tap(input, Vk.BACK)
                v.text.length > 1 -> input.sendText(v.text.substring(1))
            }
            value = TextFieldValue(" ", TextRange(1))
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Send, autoCorrect = false),
        keyboardActions = KeyboardActions(onSend = { tap(input, Vk.ENTER) }),
        modifier = Modifier.size(1.dp).focusRequester(focus),
    )
    LaunchedEffect(Unit) {
        focus.requestFocus()
        kb?.show()
    }
    DisposableEffect(Unit) { onDispose { kb?.hide() } }
    BackHandler { onClose() }
}

internal fun overlayText(cfg: StreamConfig, s: Stats): String = buildString {
    append("${s.codecName ?: cfg.mime} ${s.width}x${s.height} @ ${cfg.fps} · ${cfg.bitrateKbps / 1000} Mbps\n")
    append("FPS: %.1f · Decodificação: %.1f ms · Perda de rede: %.1f%%".format(s.fps, s.decodeMs, s.networkLossPct))
    append("\nRede (ida e volta): ${if (s.rttMs >= 0) "${s.rttMs} ms" else "—"}")
    if (s.hostMs > 0f) append(" · PC (captura+codificação): %.1f ms".format(s.hostMs))
    if (s.poorConnection) append("\nConexão ruim")
    s.warning?.let { append("\n").append(it) }
}

private fun endMessage(e: StreamState.Ended): String {
    val base = when (e.reason) {
        EndReason.USER -> "Transmissão encerrada."
        EndReason.NETWORK_LOST -> "A conexão com o PC caiu."
        EndReason.HOST_CLOSED -> "O PC encerrou a transmissão."
        EndReason.DECODER_UNSUPPORTED -> "Este aparelho não conseguiu decodificar o vídeo do PC."
    }
    return if (e.detail != null) "$base\n${e.detail}" else base
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun currentDisplay(ctx: Context): Display? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        ctx.findActivity()?.display ?: runCatching { ctx.display }.getOrNull()
    } else {
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    }
