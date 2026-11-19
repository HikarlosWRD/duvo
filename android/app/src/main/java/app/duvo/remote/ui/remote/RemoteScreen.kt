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

import androidx.compose.foundation.border
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import app.duvo.remote.R
import app.duvo.remote.ui.theme.IconTile
import app.duvo.remote.ui.theme.LocalOk
import app.duvo.remote.ui.theme.PlexMono
import app.duvo.remote.ui.theme.PrimaryButton
import app.duvo.remote.ui.theme.StatusDot
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import app.duvo.remote.host.FORBIDDEN_MESSAGE
import app.duvo.remote.host.NotPairedException
import app.duvo.remote.host.DuvoApi
import app.duvo.remote.host.PowerAction
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import android.content.BroadcastReceiver
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.duvo.remote.DuvoApp
import app.duvo.remote.host.HostClient
import app.duvo.remote.input.InputSession
import app.duvo.remote.input.InputState
import app.duvo.remote.input.PanelId
import app.duvo.remote.input.Panels
import app.duvo.remote.input.RemoteInputHost
import app.duvo.remote.input.Shortcut
import app.duvo.remote.input.Shortcuts
import app.duvo.remote.input.StickyState
import app.duvo.remote.input.TouchSample
import app.duvo.remote.input.Vk
import app.duvo.remote.net.WifiBinding
import app.duvo.remote.stream.MoonlightBridge
import app.duvo.remote.stream.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "DuvoRemote"

/** The Activity hosting the remote screen routes physical volume keys here while the screen is resumed. */
interface VolumeKeyHost {
    /** Returns true when the key was consumed; null = system handles volume keys. */
    var volumeKeyHandler: ((up: Boolean) -> Boolean)?
}

/**
 * Builds the session for paired host [hostId]: Wi-Fi lease re-resolved per attempt, host addresses
 * re-resolved per attempt (persisted + current mDNS), stream mode stopped first (ConnectionModes).
 */
private fun remoteViewModelFactory(duvo: DuvoApp, hostId: String, screenW: Int, screenH: Int) = viewModelFactory {
    initializer {
        val lease = WifiBinding.acquire(duvo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val host = RemoteInputHost(
            resolve = { duvo.discovery.endpointFor(hostId) },
            network = { lease.refresh() },
            client = { ep, net ->
                HostClient(
                    ep.addresses, ep.port, duvo.certStore,
                    deviceName = Build.MODEL ?: "Android",
                    socketFactory = net?.socketFactory,
                    expectedUniqueId = hostId,
                )
            },
        )
        val session = InputSession(
            MoonlightBridge, host, SystemClock::elapsedRealtime, scope,
            screenWidth = screenW, screenHeight = screenH,
            log = { Log.i(TAG, it) },
        )
        val owner = Any()
        duvo.connectionModes.enter(owner) { session.stop() }
        session.start()
        val api = DuvoApi { m, path, q, b, ct, t ->
            val ep = duvo.discovery.endpointFor(hostId) ?: throw NotPairedException(hostId)
            HostClient(
                ep.addresses, ep.port, duvo.certStore,
                deviceName = Build.MODEL ?: "Android",
                socketFactory = lease.network?.socketFactory,
                expectedUniqueId = hostId,
            ).duvoCall(m, path, q, b, ct, t)
        }
        RemoteViewModel(session, duvo.remotePrefs, SystemClock::uptimeMillis, api = api, density = duvo.resources.displayMetrics.density, onClose = {
            duvo.connectionModes.leave(owner)
            lease.release()
            // After session.stop(): its bridge.stop() is launched ATOMIC, so cancelling cannot skip it.
            scope.cancel()
        })
    }
}

private const val SENTINEL = "  "

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun RemoteScreen(hostId: String, onExit: () -> Unit, onPairAgain: () -> Unit, onMirror: () -> Unit = {}, onGaming: (video: Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val duvo = context.applicationContext as DuvoApp
    val metrics = context.resources.displayMetrics
    val vm: RemoteViewModel = viewModel(
        key = "remote:$hostId",
        factory = remoteViewModelFactory(
            duvo, hostId,
            maxOf(metrics.widthPixels, metrics.heightPixels), minOf(metrics.widthPixels, metrics.heightPixels),
        ),
    )
    val ui by vm.ui.collectAsState()
    val view = LocalView.current
    val settings by rememberUpdatedState(ui.settings)
    var confirm by remember { mutableStateOf<Shortcut?>(null) }
    var confirmPower by remember { mutableStateOf<PowerAction?>(null) }
    var gamingChoice by remember { mutableStateOf(false) }
    val clipboardManager = remember { context.getSystemService(ClipboardManager::class.java) }
    var showSettings by remember { mutableStateOf(false) }

    // Haptics / tap sound / confirmations.
    LaunchedEffect(vm) {
        val audio = context.getSystemService(AudioManager::class.java)
        vm.events.collect { e ->
            when (e) {
                is UiEvent.Confirm -> confirm = e.shortcut
                is UiEvent.Message -> Toast.makeText(context, e.text, Toast.LENGTH_SHORT).show()
                is UiEvent.ConfirmPower -> confirmPower = e.action
                is UiEvent.PhoneClipboardText -> {
                    clipboardManager?.setPrimaryClip(ClipData.newPlainText("Duvo", e.text))
                    Toast.makeText(context, "Texto do PC copiado.", Toast.LENGTH_SHORT).show()
                }
                is UiEvent.PhoneClipboardImage -> {
                    val uri = savePngForClipboard(context, e.png)
                    if (uri != null) {
                        clipboardManager?.setPrimaryClip(ClipData.newUri(context.contentResolver, "Duvo", uri))
                        Toast.makeText(context, "Imagem do PC copiada.", Toast.LENGTH_SHORT).show()
                    }
                }
                UiEvent.Feedback -> {
                    if (settings.haptics) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (settings.tapSound) audio?.playSoundEffect(AudioManager.FX_KEY_CLICK, -1f)
                }
            }
        }
    }

    LaunchedEffect(vm) { vm.ui.map { it.session }.distinctUntilChanged().collect { Log.i(TAG, "session: ${sessionText(it)}") } }

    // Background timer, touch reset when leaving, volume keys only while resumed.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        val host = context.findActivity() as? VolumeKeyHost
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.onScreenStart()
                Lifecycle.Event.ON_STOP -> vm.onScreenStop(SystemClock.elapsedRealtime())
                Lifecycle.Event.ON_RESUME -> {
                    host?.volumeKeyHandler = { up -> vm.onVolumeKey(up) }
                    vm.onResumedWithClipboard(phoneClipText(clipboardManager, context))
                }
                Lifecycle.Event.ON_PAUSE -> host?.volumeKeyHandler = null
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            host?.volumeKeyHandler = null
            vm.onTouch(PadTouch.Cancel)
        }
    }

    DebugCommands(vm)

    if (showSettings) {
        RemoteSettingsScreen(
            settings = ui.settings,
            onPadConfig = vm::setPadConfig,
            onHaptics = vm::setHaptics,
            onTapSound = vm::setTapSound,
            onVolumeButtons = vm::setVolumeButtons,
            onAutoClipboard = vm::setAutoClipboard,
            onMovePanel = vm::movePanel,
            onPanelHidden = vm::setPanelHidden,
            onBack = { showSettings = false },
        )
        return
    }

    BackHandler { vm.close(); onExit() }

    confirm?.let { s ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Enviar ${s.label}?") },
            text = { Text("Isso pode fechar a janela ativa no PC.") },
            confirmButton = { TextButton(onClick = { confirm = null; vm.confirmShortcut(s) }) { Text("Enviar") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } },
        )
    }

    if (gamingChoice) {
        AlertDialog(
            onDismissRequest = { gamingChoice = false },
            title = { Text("Como você quer jogar?") },
            text = { Text("Com a tela do PC, o jogo aparece no celular com os botões por cima. Só como controle, você olha o monitor ou a TV do PC e gasta menos bateria e rede.") },
            confirmButton = { TextButton(onClick = { gamingChoice = false; vm.close(); onGaming(true) }) { Text("Com a tela do PC") } },
            dismissButton = { TextButton(onClick = { gamingChoice = false; vm.close(); onGaming(false) }) { Text("Só como controle") } },
        )
    }

    confirmPower?.let { a ->
        AlertDialog(
            onDismissRequest = { confirmPower = null },
            title = { Text("${a.label} o PC?") },
            text = { Text(if (a == PowerAction.LOCK) "O PC vai para a tela de bloqueio." else "Programas abertos podem pedir para salvar o trabalho.") },
            confirmButton = { TextButton(onClick = { confirmPower = null; vm.confirmPower(a) }) { Text(a.label) } },
            dismissButton = { TextButton(onClick = { confirmPower = null }) { Text("Cancelar") } },
        )
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }
    var committed by remember { mutableStateOf(SENTINEL) }

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconTile(R.drawable.ic_back, "Sair", { vm.close(); onExit() }, outlined = false)
            Column(Modifier.weight(1f)) {
                StatusDot(
                    sessionText(ui.session),
                    color = if (ui.session == InputState.Connected) LocalOk.current else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val line = ui.status.firstOrNull()
                if (line != null) {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = PlexMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconTile(R.drawable.ic_cast, "Espelhar tela", { vm.close(); onMirror() })
            IconTile(R.drawable.ic_gamepad, "Gaming", { gamingChoice = true })
            IconTile(R.drawable.ic_sliders, "Configurações", { showSettings = true })
        }

        val ended = ui.session as? InputState.Ended
        if (ended != null) {
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ended.reason, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { vm.close(); onExit() }) { Text("Voltar") }
                if (ended.reason == InputSession.NOT_PAIRED_REASON || ended.reason == InputSession.CERT_CHANGED_REASON) {
                    OutlinedButton(onClick = { vm.close(); onPairAgain() }) { Text("Parear de novo") }
                }
            }
            return@Column
        }

        // Hidden field that receives the soft keyboard; edits are diffed into text/Backspace/Enter.
        BasicTextField(
            value = field,
            onValueChange = { v ->
                field = v
                if (v.composition == null) {
                    vm.onTyping(committed, v.text)
                    if (v.text.length > 64 || !v.text.startsWith(SENTINEL)) {
                        field = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
                        committed = SENTINEL
                    } else {
                        committed = v.text
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                // No suggestions/composition: each key reaches the PC as it is typed.
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { vm.onKey(Vk.ENTER) }),
            modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focus),
        )

        Row(Modifier.fillMaxWidth().weight(1f).heightIn(min = 96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
                        .pointerInteropFilter { e -> e.toPadTouch()?.let(vm::onTouch); true },
                    contentAlignment = Alignment.Center,
                ) {}
                ScrollStrip(vertical = false, vm = vm, modifier = Modifier.fillMaxWidth().height(28.dp))
            }
            ScrollStrip(vertical = true, vm = vm, modifier = Modifier.fillMaxHeight().width(28.dp))
        }

        val left = NativeBridge.BUTTON_LEFT to "Esquerdo"
        val right = NativeBridge.BUTTON_RIGHT to "Direito"
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            (if (ui.settings.pad.leftHanded) listOf(right, left) else listOf(left, right)).forEach { (button, label) ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        .pointerInput(button) {
                            awaitEachGesture {
                                awaitFirstDown()
                                vm.onButton(button, down = true)
                                waitForUpOrCancellation()
                                vm.onButton(button, down = false)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface) }
            }
        }

        if (ui.panels.isNotEmpty()) {
            val selected = ui.panels.indexOf(ui.activePanel).coerceAtLeast(0)
            ScrollableTabRow(
                selectedTabIndex = selected,
                edgePadding = 0.dp,
                containerColor = Color.Transparent,
                divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outline) },
            ) {
                ui.panels.forEach { p ->
                    Tab(
                        selected = p == ui.activePanel,
                        onClick = { vm.selectPanel(p) },
                        selectedContentColor = MaterialTheme.colorScheme.onSurface,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        icon = { androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(panelIcon(p)), contentDescription = null, modifier = Modifier.size(22.dp)) },
                        text = { Text(panelLabel(p), style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (val p = ui.activePanel) {
                PanelId.KEYBOARD -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        val clip = clipboardManager?.primaryClip
                        vm.sendClipboard(phoneClipText(clipboardManager, context), phoneClipPng(clip, context))
                    }) { Text("Enviar p/ o PC") }
                    OutlinedButton(onClick = { vm.pullClipboard() }) { Text("Pegar do PC") }
                }
                else -> Unit
            }
            when (val p = ui.activePanel) {
                PanelId.KEYBOARD -> KeyboardPanel(
                    ui = ui,
                    onOpenKeyboard = { focus.requestFocus(); keyboard?.show() },
                    onSticky = vm::tapSticky,
                    onKey = vm::onKey,
                    onShortcut = vm::runShortcut,
                )
                PanelId.MEDIA, PanelId.BROWSER, PanelId.PRESENTATION -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Panels.actions(p).forEach { a -> OutlinedButton(onClick = { vm.runAction(a) }) { Text(a.label) } }
                }
                PanelId.APPS -> if (ui.access?.windows == false) Blocked() else AppsPanel(ui, vm)
                PanelId.POWER -> if (ui.access?.power == false) Blocked() else FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PowerAction.values().forEach { a -> OutlinedButton(onClick = { vm.requestPower(a) }) { Text(a.label) } }
                }
                null -> Text("Todos os painéis estão ocultos. Mostre algum em Configurações.")
            }
        }
    }
}

private val SPECIAL_KEYS: List<Pair<String, Short>> = listOf(
    "Esc" to Vk.ESC, "Tab" to Vk.TAB, "Enter" to Vk.ENTER, "Apagar" to Vk.BACK, "Del" to Vk.DEL,
    "←" to Vk.LEFT, "↑" to Vk.UP, "↓" to Vk.DOWN, "→" to Vk.RIGHT,
    "Home" to Vk.HOME, "End" to Vk.END, "PgUp" to Vk.PGUP, "PgDn" to Vk.PGDN,
    "Win" to Vk.LWIN, "PrtSc" to Vk.SNAPSHOT,
) + (0 until 12).map { "F${it + 1}" to (Vk.F1 + it).toShort() }

private val STICKY: List<Pair<String, Byte>> = listOf(
    "Ctrl" to NativeBridge.MOD_CTRL, "Shift" to NativeBridge.MOD_SHIFT,
    "Alt" to NativeBridge.MOD_ALT, "Win" to NativeBridge.MOD_META,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyboardPanel(
    ui: RemoteUi,
    onOpenKeyboard: () -> Unit,
    onSticky: (Byte) -> Unit,
    onKey: (Short) -> Unit,
    onShortcut: (Shortcut) -> Unit,
) {
    PrimaryButton("Abrir teclado", onOpenKeyboard, icon = R.drawable.ic_keyboard)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        STICKY.forEach { (label, mod) ->
            val st = ui.sticky[mod] ?: StickyState.OFF
            FilterChip(
                selected = st != StickyState.OFF,
                onClick = { onSticky(mod) },
                label = {
                    Text(
                        when (st) {
                            StickyState.OFF -> label
                            StickyState.ONCE -> "$label (1x)"
                            StickyState.LOCKED -> "$label (fixo)"
                        },
                    )
                },
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SPECIAL_KEYS.forEach { (label, vk) -> OutlinedButton(onClick = { onKey(vk) }) { Text(label) } }
    }
    Text("Atalhos", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Shortcuts.all.forEach { s -> OutlinedButton(onClick = { onShortcut(s) }) { Text(s.label) } }
    }
}

/** MotionEvent -> [PadTouch]: every pointer, historical samples first, one timestamp per batch. */
private fun MotionEvent.toPadTouch(): PadTouch? {
    fun sample(i: Int) = TouchSample(getPointerId(i), getX(i), getY(i), eventTime)
    return when (actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> PadTouch.Down(sample(actionIndex))
        MotionEvent.ACTION_MOVE -> PadTouch.Move(
            buildList {
                for (h in 0 until historySize) {
                    val t = getHistoricalEventTime(h)
                    for (p in 0 until pointerCount) add(TouchSample(getPointerId(p), getHistoricalX(p, h), getHistoricalY(p, h), t))
                }
                for (p in 0 until pointerCount) add(sample(p))
            },
        )
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> PadTouch.Up(sample(actionIndex))
        MotionEvent.ACTION_CANCEL -> PadTouch.Cancel
        else -> null
    }
}

/**
 * DEBUG BUILDS ONLY (debuggable apps): adb hook for live checks, e.g.
 * `am broadcast -p app.duvo.remote -a app.duvo.remote.debug.REMOTE --es text "Olá ç"`.
 * Extras: text (String), key (int VK), sticky (ctrl|shift|alt|win), action (PANEL:id), shortcut (id),
 * scroll (int, high-res units), volume (up|down).
 */
@Composable
private fun DebugCommands(vm: RemoteViewModel) {
    val context = LocalContext.current
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    DisposableEffect(vm) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                i ?: return
                Log.i(TAG, "debug command: ${i.extras?.keySet()}")
                i.getStringExtra("sticky")?.let { name ->
                    STICKY.firstOrNull { it.first.equals(name, true) || (name == "win" && it.first == "Win") }
                        ?.let { vm.tapSticky(it.second) }
                }
                i.getStringExtra("text")?.let(vm::onText)
                if (i.hasExtra("key")) vm.onKey(i.getIntExtra("key", 0).toShort())
                i.getStringExtra("action")?.split(':')?.takeIf { it.size == 2 }?.let { (panel, id) ->
                    runCatching { Panels.actions(PanelId.valueOf(panel)).single { it.id == id } }.getOrNull()?.let(vm::runAction)
                }
                i.getStringExtra("shortcut")?.let { id -> Shortcuts.all.firstOrNull { it.id == id }?.let(vm::runShortcut) }
                if (i.hasExtra("scroll")) vm.perform(app.duvo.remote.input.PadAction.Scroll(i.getIntExtra("scroll", 0), 0))
                i.getStringExtra("volume")?.let { vm.onVolumeKey(it == "up") }
            }
        }
        val filter = IntentFilter(DEBUG_ACTION)
        // Exported so `adb shell am broadcast` reaches it; debuggable builds only.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }
}

private const val DEBUG_ACTION = "app.duvo.remote.debug.REMOTE"

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppsPanel(ui: RemoteUi, vm: RemoteViewModel) {
    LaunchedEffect(Unit) { vm.refreshApps() }
    var query by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.refreshApps() }) { Text(if (ui.appsLoading) "Atualizando…" else "Atualizar") }
        OutlinedButton(onClick = { vm.showDesktop() }) { Text("Mostrar área de trabalho") }
    }
    androidx.compose.material3.OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Buscar app pelo nome") },
        leadingIcon = { androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_search), contentDescription = null, modifier = Modifier.size(20.dp)) },
        shape = RoundedCornerShape(14.dp),
    )
    val q = query.trim().lowercase()
    val windows = if (q.isEmpty()) ui.windows else ui.windows.filter { it.title.lowercase().contains(q) || it.process.lowercase().contains(q) }
    val apps = (if (q.isEmpty()) ui.apps else ui.apps.filter { it.name.lowercase().contains(q) }).take(60)
    if (windows.isNotEmpty()) {
        Text("Janelas abertas", style = MaterialTheme.typography.titleSmall)
        windows.forEach { w ->
            TextButton(onClick = { vm.activateWindow(w) }) {
                Text("${w.title} (${w.process.removeSuffix(".exe")})", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Text("Abrir app", style = MaterialTheme.typography.titleSmall)
    if (apps.isEmpty() && q.isNotEmpty()) Text("Nenhum app com esse nome.", style = MaterialTheme.typography.bodySmall)
    apps.forEach { a ->
        LaunchedEffect(a.id) { vm.ensureIcon(a.id) }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { vm.launchApp(a) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val png = ui.appIcons[a.id]
            val bmp = remember(png) { png?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
            if (bmp != null) {
                androidx.compose.foundation.Image(bmp, contentDescription = null, modifier = Modifier.size(36.dp))
            } else {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
            }
            Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun phoneClipText(cm: ClipboardManager?, context: Context): String? = runCatching {
    cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

/** PNG bytes of an image on the phone clipboard (re-encoded), or null. */
private fun phoneClipPng(clip: ClipData?, context: Context): ByteArray? = runCatching {
    val uri = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: return null
    val type = context.contentResolver.getType(uri) ?: return null
    if (!type.startsWith("image/")) return null
    context.contentResolver.openInputStream(uri)?.use { input ->
        val bmp = BitmapFactory.decodeStream(input) ?: return null
        java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}.getOrNull()

/** Saves the PC image in the app cache and returns a content:// URI (FileProvider) for the clipboard. */
private fun savePngForClipboard(context: Context, png: ByteArray): Uri? = runCatching {
    val dir = java.io.File(context.cacheDir, "clipboard").apply { mkdirs() }
    val f = java.io.File(dir, "pc-clipboard.png")
    f.writeBytes(png)
    FileProvider.getUriForFile(context, "${context.packageName}.clipboard", f)
}.getOrNull()

@Composable
private fun Blocked() {
    Text(FORBIDDEN_MESSAGE, style = MaterialTheme.typography.bodyMedium)
}

/** Edge strip of the trackpad: drag along it to scroll (vertical strip = up/down, bottom strip = left/right). */
@Composable
private fun ScrollStrip(vertical: Boolean, vm: RemoteViewModel, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .pointerInput(vertical) {
                if (vertical) {
                    detectVerticalDragGestures(
                        onDragEnd = { vm.endScrollStrip() },
                        onDragCancel = { vm.endScrollStrip() },
                    ) { c, d -> c.consume(); vm.onScrollStrip(0f, d) }
                } else {
                    detectHorizontalDragGestures(
                        onDragEnd = { vm.endScrollStrip() },
                        onDragCancel = { vm.endScrollStrip() },
                    ) { c, d -> c.consume(); vm.onScrollStrip(d, 0f) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .then(if (vertical) Modifier.width(3.dp).height(44.dp) else Modifier.height(3.dp).width(44.dp))
                .clip(RoundedCornerShape(2.dp))
                .background(line),
        )
    }
}

private fun panelIcon(p: PanelId): Int = when (p) {
    PanelId.KEYBOARD -> R.drawable.ic_keyboard
    PanelId.MEDIA -> R.drawable.ic_media
    PanelId.BROWSER -> R.drawable.ic_globe
    PanelId.PRESENTATION -> R.drawable.ic_slides
    PanelId.APPS -> R.drawable.ic_apps
    PanelId.POWER -> R.drawable.ic_power
}
