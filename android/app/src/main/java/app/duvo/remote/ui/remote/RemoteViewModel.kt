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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.duvo.remote.host.ApiForbidden
import app.duvo.remote.host.FORBIDDEN_MESSAGE
import app.duvo.remote.host.IMAGE_TOO_LARGE_MESSAGE
import app.duvo.remote.host.MAX_CLIPBOARD_IMAGE_BYTES
import app.duvo.remote.host.DuvoApi
import app.duvo.remote.host.DuvoStatus
import app.duvo.remote.host.PcApp
import app.duvo.remote.host.PcWindow
import app.duvo.remote.host.PowerAction
import app.duvo.remote.input.InputState
import app.duvo.remote.input.KeyEvent
import app.duvo.remote.input.PadAction
import app.duvo.remote.input.PadConfig
import app.duvo.remote.input.PanelAction
import app.duvo.remote.input.PanelId
import app.duvo.remote.input.RemoteInput
import app.duvo.remote.input.RemotePrefs
import app.duvo.remote.input.Shortcut
import app.duvo.remote.input.StickyModifiers
import app.duvo.remote.input.StickyState
import app.duvo.remote.input.TouchSample
import app.duvo.remote.input.TrackpadGestures
import app.duvo.remote.input.Vk
import app.duvo.remote.input.chordEvents
import app.duvo.remote.stream.NativeBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One touch event on the trackpad, already split per pointer (see RemoteScreen's MotionEvent mapping). */
sealed interface PadTouch {
    data class Down(val sample: TouchSample) : PadTouch

    /** Every pointer of one ACTION_MOVE, historical samples first, each batch sharing one timestamp. */
    data class Move(val samples: List<TouchSample>) : PadTouch
    data class Up(val sample: TouchSample) : PadTouch

    /** ACTION_CANCEL: the gesture is void; a held drag button is released. */
    data object Cancel : PadTouch
}

sealed interface UiEvent {
    /** [shortcut] needs the user's confirmation (Alt+F4) before [RemoteViewModel.confirmShortcut]. */
    data class Confirm(val shortcut: Shortcut) : UiEvent

    /** A key/button fired: vibrate and/or click per prefs. */
    data object Feedback : UiEvent

    /** Short pt-BR message to show (errors, confirmations). */
    data class Message(val text: String) : UiEvent

    /** Power actions always need the user's confirmation before [RemoteViewModel.confirmPower]. */
    data class ConfirmPower(val action: PowerAction) : UiEvent

    /** Put the PC's clipboard content on the phone. */
    data class PhoneClipboardText(val text: String) : UiEvent
    class PhoneClipboardImage(val png: ByteArray) : UiEvent
}

/** Status bar lines from the PC status. */
fun statusLines(s: DuvoStatus): List<String> = buildList {
    val app = s.activeApp.removeSuffix(".exe")
    val first = listOf(app, s.windowTitle).filter { it.isNotBlank() }.joinToString(" \u2014 ")
    if (first.isNotEmpty()) add(first)
    if (s.mediaTitle.isNotBlank()) {
        val media = listOf(s.mediaTitle, s.mediaArtist).filter { it.isNotBlank() }.joinToString(" \u2014 ")
        add((if (s.mediaPlaying) "Tocando: " else "Pausado: ") + media)
    }
    s.volume?.let { add(if (s.muted) "Volume: mudo" else "Volume: $it%") }
}

/** Snapshot of [RemotePrefs] for the screens. */
data class RemoteSettings(
    val pad: PadConfig = PadConfig(),
    val haptics: Boolean = true,
    val tapSound: Boolean = false,
    val volumeButtonsControlPc: Boolean = true,
    val autoClipboardOnResume: Boolean = false,
    val panelOrder: List<PanelId> = PanelId.values().toList(),
    val hidden: Set<PanelId> = emptySet(),
)

data class RemoteUi(
    val session: InputState = InputState.Connecting,
    /** Visible panel tabs, in the user's order. */
    val panels: List<PanelId> = emptyList(),
    val activePanel: PanelId? = null,
    val sticky: Map<Byte, StickyState> = emptyMap(),
    val settings: RemoteSettings = RemoteSettings(),
    /** Status bar lines (active app/window, media, volume). */
    val status: List<String> = emptyList(),
    /** What the PC lets this phone do; null until read. */
    val access: app.duvo.remote.host.AccessInfo? = null,
    val windows: List<PcWindow> = emptyList(),
    val apps: List<PcApp> = emptyList(),
    val appsLoading: Boolean = false,
    /** PNG bytes of app icons already fetched, by app id. */
    val appIcons: Map<String, ByteArray> = emptyMap(),
)

/** pt-BR line for the session state. */
fun sessionText(s: InputState): String = when (s) {
    InputState.Connecting -> "Conectando…"
    InputState.Connected -> "Conectado"
    is InputState.Reconnecting ->
        if (s.nextInMs > 0) "Reconectando… (tentativa ${s.attempt}, em ${(s.nextInMs + 999) / 1000} s)"
        else "Reconectando… (tentativa ${s.attempt})"
    is InputState.Ended -> s.reason
}

const val TEXT_CHUNK_MAX = 512

/** Splits [text] into pieces of at most [max] chars, never between the two halves of a surrogate pair. */
fun chunkText(text: String, max: Int = TEXT_CHUNK_MAX): List<String> {
    require(max >= 2)
    val out = ArrayList<String>()
    var i = 0
    while (i < text.length) {
        var end = minOf(i + max, text.length)
        if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
        out += text.substring(i, end)
        i = end
    }
    return out
}

/** What an edit of the hidden keyboard field means for the PC. */
sealed interface TypingOp {
    data class Text(val text: String) : TypingOp
    data class Key(val vk: Short) : TypingOp
}

/**
 * Turns an edit of the hidden text field ([old] -> [new]) into PC input: characters removed after the
 * common prefix become Backspaces (also how an autocorrect replacement arrives), inserted text is
 * typed, and newlines become Enter.
 */
fun typingOps(old: String, new: String): List<TypingOp> {
    var p = 0
    val n = minOf(old.length, new.length)
    while (p < n && old[p] == new[p]) p++
    // Never split between the halves of a surrogate pair.
    if (p > 0 && Character.isHighSurrogate(old[p - 1]) && p < old.length) p--
    val out = ArrayList<TypingOp>()
    repeat(old.codePointCount(p, old.length)) { out += TypingOp.Key(Vk.BACK) }
    val parts = new.substring(p).split('\n')
    parts.forEachIndexed { i, part ->
        if (part.isNotEmpty()) out += TypingOp.Text(part)
        if (i < parts.lastIndex) out += TypingOp.Key(Vk.ENTER)
    }
    return out
}

/** Virtual key for a typed letter/digit/space (sticky modifiers turn "a" into Ctrl+A), else null. */
internal fun vkForChar(c: Char): Short? = when (c) {
    in 'a'..'z' -> (Vk.A + (c - 'a')).toShort()
    in 'A'..'Z' -> (Vk.A + (c - 'A')).toShort()
    in '0'..'9' -> (Vk.N0 + (c - '0')).toShort()
    ' ' -> VK_SPACE
    else -> null
}

private const val VK_SPACE: Short = 0x20

/**
 * Remote Control mode: turns trackpad touches, typed text, special keys, panel actions and shortcuts
 * into input on [session]. All calls come from the main thread (Compose/Activity callbacks) and the
 * drag-lock ticker runs on [scope] (main too), so [TrackpadGestures] needs no locking.
 * [clock] must be on MotionEvent's timebase (SystemClock.uptimeMillis).
 */
class RemoteViewModel(
    private val session: RemoteInput,
    private val prefs: RemotePrefs,
    private val clock: () -> Long,
    scope: CoroutineScope? = null,
    /** Extra cleanup when the screen closes (Wi-Fi lease, ConnectionModes). */
    private val onClose: () -> Unit = {},
    /** Duvo host API (status bar, apps, power, clipboard); null disables those features. */
    private val api: DuvoApi? = null,
    /** Screen density: pad thresholds in [PadConfig] are dp, the touch samples are pixels. */
    private val density: Float = 1f,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val sticky = StickyModifiers()
    private var gestures = TrackpadGestures(scaled(prefs.padConfig))
    private var stripV = 0f
    private var stripH = 0f
    private val down = HashSet<Int>()
    private var ticker: Job? = null
    private var closed = false

    private val _ui = MutableStateFlow(RemoteUi(session = session.state.value, sticky = sticky.state))
    val ui: StateFlow<RemoteUi> = _ui.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    init {
        refreshSettings()
        this.scope.launch {
            session.state.collect { s ->
                val was = _ui.value.session
                _ui.update { it.copy(session = s) }
                // Connection lost or ended: drop touch state (the host releases its buttons on disconnect).
                if (was == InputState.Connected && s != InputState.Connected) resetGestures()
                if (s == InputState.Connected) startStatus() else stopStatus()
            }
        }
    }

    // ---- Duvo API: status bar, apps, power, clipboard ----
    private var statusJob: Job? = null

    private fun startStatus() {
        val a = api ?: return
        if (statusJob?.isActive == true) return
        statusJob = scope.launch {
            runCatching { a.access() }.getOrNull()?.let { acc -> _ui.update { it.copy(access = acc) } }
            var since = -1L
            while (isActive) {
                try {
                    val st = if (since < 0) a.status() else a.events(since)
                    since = st.seq
                    _ui.update { it.copy(status = statusLines(st)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiForbidden) {
                    _ui.update { it.copy(status = emptyList()) }
                    return@launch
                } catch (e: Exception) {
                    since = -1
                    delay(STATUS_RETRY_MS)
                }
            }
        }
    }

    private fun stopStatus() {
        statusJob?.cancel()
        statusJob = null
    }

    private fun apiCall(block: suspend (DuvoApi) -> Unit) {
        val a = api ?: return
        scope.launch {
            try {
                block(a)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(UiEvent.Message(e.message ?: FORBIDDEN_MESSAGE))
            }
        }
    }

    fun refreshApps() {
        _ui.update { it.copy(appsLoading = true) }
        apiCall { a ->
            try {
                val w = a.windows()
                val apps = a.apps().distinctBy { it.name.lowercase() }.sortedBy { it.name.lowercase() }
                _ui.update { it.copy(windows = w, apps = apps) }
            } finally {
                _ui.update { it.copy(appsLoading = false) }
            }
        }
    }

    private val iconRequested = HashSet<String>()

    /** Fetches the icon of [id] once; failures are silent (the row just keeps no icon). */
    fun ensureIcon(id: String) {
        val a = api ?: return
        if (!iconRequested.add(id)) return
        scope.launch {
            try {
                a.appIcon(id)?.let { png -> _ui.update { it.copy(appIcons = it.appIcons + (id to png)) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                iconRequested.remove(id)
            }
        }
    }

    fun activateWindow(w: PcWindow) = apiCall { it.activate(w.id) }
    fun launchApp(app: PcApp) = apiCall { it.launch(app.id); _events.tryEmit(UiEvent.Message("Abrindo ${app.name}\u2026")) }
    fun showDesktop() = apiCall { it.showDesktop() }

    /** Asks for confirmation; nothing is sent to the PC until [confirmPower]. */
    fun requestPower(a: PowerAction) { _events.tryEmit(UiEvent.ConfirmPower(a)) }
    fun confirmPower(a: PowerAction) = apiCall { it.power(a) }

    /** Phone to PC. Empty content sends nothing. */
    fun sendClipboard(text: String?, png: ByteArray?) {
        if (png != null && png.size > MAX_CLIPBOARD_IMAGE_BYTES) {
            _events.tryEmit(UiEvent.Message(IMAGE_TOO_LARGE_MESSAGE)); return
        }
        if (png != null && png.isNotEmpty()) {
            apiCall { it.setImage(png); _events.tryEmit(UiEvent.Message("Imagem enviada ao PC.")) }; return
        }
        if (text.isNullOrEmpty()) {
            _events.tryEmit(UiEvent.Message("A \u00e1rea de transfer\u00eancia do celular est\u00e1 vazia.")); return
        }
        apiCall { it.setText(text); _events.tryEmit(UiEvent.Message("Texto enviado ao PC.")) }
    }

    /** PC to phone: image if the PC has one, else text. */
    fun pullClipboard() = apiCall { a ->
        val img = runCatching { a.getImage() }.getOrNull()
        if (img != null) {
            _events.tryEmit(UiEvent.PhoneClipboardImage(img)); return@apiCall
        }
        val t = a.getText()
        if (t.isNullOrEmpty()) _events.tryEmit(UiEvent.Message("A \u00e1rea de transfer\u00eancia do PC est\u00e1 vazia."))
        else _events.tryEmit(UiEvent.PhoneClipboardText(t))
    }

    /** App came back to the foreground: send the phone's text if the user enabled it. */
    fun onResumedWithClipboard(text: String?) {
        if (prefs.autoClipboardOnResume && !text.isNullOrEmpty() && session.state.value == InputState.Connected) {
            apiCall { it.setText(text) }
        }
    }

    // ---- trackpad ----

    fun onTouch(event: PadTouch) {
        when (event) {
            is PadTouch.Down -> {
                down += event.sample.pointerId
                perform(gestures.down(event.sample))
                startTicker()
            }
            is PadTouch.Move -> event.samples.forEach { perform(gestures.move(it)) }
            is PadTouch.Up -> {
                down -= event.sample.pointerId
                perform(gestures.up(event.sample))
                if (down.isEmpty()) stopTicker()
            }
            PadTouch.Cancel -> resetGestures()
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                perform(gestures.tick(clock()))
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun resetGestures() {
        down.clear()
        stopTicker()
        perform(gestures.reset())
    }

    private fun perform(actions: List<PadAction>) = actions.forEach(::perform)

    internal fun perform(a: PadAction) {
        when (a) {
            is PadAction.Move -> session.sendMouseMove(a.dx, a.dy)
            is PadAction.PointTo -> Unit // only produced by MirrorGestures
            is PadAction.Click -> {
                session.sendMouseButton(true, a.button)
                session.sendMouseButton(false, a.button)
                feedback()
            }
            is PadAction.Press -> {
                session.sendMouseButton(true, a.button)
                feedback()
            }
            is PadAction.Release -> session.sendMouseButton(false, a.button)
            is PadAction.Scroll -> {
                if (a.v != 0) session.sendScroll(a.v.toShortClamped())
                if (a.h != 0) session.sendHScroll(a.h.toShortClamped())
            }
            // Gesture chords (3-finger swipes) leave the sticky modifiers armed for the next key.
            is PadAction.Chord -> send(chordEvents(a.vk, a.modifiers))
            is PadAction.Zoom -> {
                session.sendKey(Vk.CONTROL, true, NativeBridge.MOD_CTRL)
                session.sendScroll((a.steps * NOTCH).toShortClamped())
                session.sendKey(Vk.CONTROL, false, NativeBridge.MOD_CTRL)
            }
        }
    }

    /** On-screen mouse buttons: press on touch down, release on up (hold one and drag with another finger). */
    fun onButton(button: Int, down: Boolean) {
        session.sendMouseButton(down, button)
        if (down) feedback()
    }

    // ---- keyboard ----

    fun onText(text: String) {
        if (text.isEmpty()) return
        // A letter typed while a sticky modifier is armed is a shortcut (Ctrl + "a" = Ctrl+A), not text.
        if (text.length == 1 && sticky.state.values.any { it != StickyState.OFF }) {
            vkForChar(text[0])?.let { onKey(it); return }
        }
        chunkText(text).forEach(session::sendText)
    }

    /** Applies one committed edit of the hidden keyboard field. */
    fun onTyping(old: String, new: String) {
        typingOps(old, new).forEach {
            when (it) {
                is TypingOp.Text -> onText(it.text)
                is TypingOp.Key -> onKey(it.vk)
            }
        }
    }

    fun onKey(vk: Short) = fire(listOf(vk), 0)

    fun tapSticky(mod: Byte) {
        sticky.tap(mod)
        publishSticky()
        feedback()
    }

    fun runAction(a: PanelAction) = fire(a.keys, a.modifiers)

    fun runShortcut(s: Shortcut) {
        if (s.confirm) {
            _events.tryEmit(UiEvent.Confirm(s))
            return
        }
        fire(s.keys, s.modifiers)
    }

    fun confirmShortcut(s: Shortcut) = fire(s.keys, s.modifiers)

    /** R10: the armed sticky modifiers join the chord, and ONCE ones are consumed by it. */
    private fun fire(keys: List<Short>, modifiers: Byte) {
        val mods = (modifiers.toInt() or sticky.consumeForKey().toInt()).toByte()
        publishSticky()
        send(chordEvents(keys, mods))
        feedback()
    }

    private fun send(events: List<KeyEvent>) = events.forEach { session.sendKey(it.vk, it.down, it.modifiers) }

    /** Physical volume key while the screen is resumed: true when consumed (sent to the PC). */
    fun onVolumeKey(up: Boolean): Boolean {
        if (!prefs.volumeButtonsControlPc) return false
        // Disconnected: let the phone handle its own volume instead of swallowing the key.
        if (session.state.value != InputState.Connected) return false
        send(chordEvents(listOf(if (up) Vk.VOL_UP else Vk.VOL_DOWN), 0))
        return true
    }

    private fun publishSticky() = _ui.update { it.copy(sticky = sticky.state) }

    private fun feedback() {
        _events.tryEmit(UiEvent.Feedback)
    }

    // ---- panels ----

    fun selectPanel(p: PanelId) = _ui.update { it.copy(activePanel = p) }

    fun movePanel(p: PanelId, delta: Int) {
        val order = prefs.panelOrder.toMutableList()
        val i = order.indexOf(p)
        if (i < 0) return
        val j = (i + delta).coerceIn(0, order.lastIndex)
        if (i == j) return
        order.removeAt(i)
        order.add(j, p)
        prefs.panelOrder = order
        refreshSettings()
    }

    fun setPanelHidden(p: PanelId, hidden: Boolean) {
        prefs.hidden = if (hidden) prefs.hidden + p else prefs.hidden - p
        refreshSettings()
    }

    // ---- settings ----

    fun setPadConfig(cfg: PadConfig) {
        prefs.padConfig = cfg
        resetGestures()
        gestures = TrackpadGestures(scaled(prefs.padConfig))
        refreshSettings()
    }

    /** Thresholds are stored as dp-like values; convert to the pixels of the touch events. */
    private fun scaled(c: PadConfig) = c.copy(
        tapSlopPx = c.tapSlopPx * density * 1.5f,
        tapMaxMs = maxOf(c.tapMaxMs, 250L),
        scrollPxPerNotch = c.scrollPxPerNotch * density,
        pinchPxPerStep = c.pinchPxPerStep * density,
        swipePx = c.swipePx * density,
    )

    /** Scroll strip at the trackpad edge: dragging down scrolls the page down, like a scroll bar. */
    fun onScrollStrip(dxPx: Float, dyPx: Float) {
        val per = prefs.padConfig.scrollPxPerNotch * density / 2f
        stripV += dyPx
        stripH += dxPx
        val v = -(stripV / per * NOTCH).toInt()
        val h = (stripH / per * NOTCH).toInt()
        if (v != 0 || h != 0) {
            perform(listOf(PadAction.Scroll(v, h)))
            if (v != 0) stripV += v * per / NOTCH
            if (h != 0) stripH -= h * per / NOTCH
        }
    }
    fun endScrollStrip() { stripV = 0f; stripH = 0f }

    fun setHaptics(on: Boolean) { prefs.haptics = on; refreshSettings() }
    fun setTapSound(on: Boolean) { prefs.tapSound = on; refreshSettings() }
    fun setVolumeButtons(on: Boolean) { prefs.volumeButtonsControlPc = on; refreshSettings() }
    fun setAutoClipboard(on: Boolean) { prefs.autoClipboardOnResume = on; refreshSettings() }

    private fun refreshSettings() {
        val s = RemoteSettings(
            pad = prefs.padConfig,
            haptics = prefs.haptics,
            tapSound = prefs.tapSound,
            volumeButtonsControlPc = prefs.volumeButtonsControlPc,
            autoClipboardOnResume = prefs.autoClipboardOnResume,
            panelOrder = prefs.panelOrder,
            hidden = prefs.hidden,
        )
        val visible = s.panelOrder.filter { it !in s.hidden }
        _ui.update { u ->
            u.copy(
                settings = s,
                panels = visible,
                activePanel = u.activePanel?.takeIf { it in visible } ?: nextVisible(u.activePanel, s.panelOrder, visible),
            )
        }
    }

    /** The panel after [gone] in [order] that is still visible (else the first visible one). */
    private fun nextVisible(gone: PanelId?, order: List<PanelId>, visible: List<PanelId>): PanelId? {
        if (gone != null) {
            val i = order.indexOf(gone)
            order.drop(i + 1).firstOrNull { it in visible }?.let { return it }
        }
        return visible.firstOrNull()
    }

    // ---- lifecycle ----

    /** The screen left the foreground: release held buttons, start the background timer. */
    fun onScreenStop(nowMs: Long) {
        resetGestures()
        session.onBackgrounded(nowMs)
    }

    fun onScreenStart() = session.onForegrounded()

    /** Leaves remote mode: releases what is held and ends the session. Idempotent. */
    fun close() {
        if (closed) return
        closed = true
        resetGestures()
        stopStatus()
        session.stop()
        onClose()
    }

    override fun onCleared() = close()

    private companion object {
        const val STATUS_RETRY_MS = 2000L
        const val TICK_MS = 50L
        const val NOTCH = 120

        fun Int.toShortClamped(): Short = coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
