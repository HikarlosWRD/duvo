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
package app.duvo.remote.ui.stream

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import app.duvo.remote.gamepad.ControlKind
import app.duvo.remote.gamepad.GamepadState
import app.duvo.remote.gamepad.PadControl
import app.duvo.remote.gamepad.PadLayout
import app.duvo.remote.gamepad.VirtualPadEngine
import app.duvo.remote.input.MirrorGestures
import app.duvo.remote.input.PadAction
import app.duvo.remote.input.PadConfig
import app.duvo.remote.input.TouchSample
import app.duvo.remote.input.Vk
import app.duvo.remote.input.VideoRect
import app.duvo.remote.stream.NativeBridge
import app.duvo.remote.stream.TouchMode
import kotlin.math.roundToInt

/** Draws one control at its layout position; [pressed] lights it, [knob] moves a stick's knob (-1..1). */
@Composable
fun PadControlView(c: PadControl, w: Float, h: Float, pressed: Boolean, knob: Pair<Float, Float> = 0f to 0f, selected: Boolean = false, dpad: Int = 0) {
    val density = LocalDensity.current
    val d = c.size * h
    val shape = when (c.kind) {
        ControlKind.TRIGGER_LEFT, ControlKind.TRIGGER_RIGHT -> RoundedCornerShape(30)
        else -> CircleShape
    }
    if (c.kind == ControlKind.DPAD) {
        DpadArrows(c, w, h, dpad, selected)
        return
    }
    val fill = Color.White.copy(alpha = c.opacity * if (pressed) 0.75f else 0.25f)
    val edge = if (selected) Color(0xFFFFC107) else Color.White.copy(alpha = c.opacity)
    Box(
        Modifier
            .offset { IntOffset((c.x * w - d / 2).roundToInt(), (c.y * h - d / 2).roundToInt()) }
            .size(with(density) { d.toDp() })
            .background(fill, shape)
            .border(with(density) { (if (selected) 3f else 1.5f).toDp() }, edge, shape),
        contentAlignment = Alignment.Center,
    ) {
        when (c.kind) {
            ControlKind.STICK_LEFT, ControlKind.STICK_RIGHT -> {
                val k = d * 0.4f
                Box(
                    Modifier
                        .offset { IntOffset((knob.first * (d - k) / 2).roundToInt(), (knob.second * (d - k) / 2).roundToInt()) }
                        .size(with(density) { k.toDp() })
                        .background(Color.White.copy(alpha = c.opacity * 0.8f), CircleShape),
                )
            }
            ControlKind.DPAD -> Text("✚", color = Color.White.copy(alpha = c.opacity), fontSize = with(density) { (d * 0.55f).toSp() })
            else -> Text(c.label, color = Color.White.copy(alpha = maxOf(c.opacity, 0.5f)), fontSize = with(density) { (d * 0.3f).toSp() })
        }
    }
}

/** On-screen gamepad: multi-touch through [VirtualPadEngine], [onState] on every change. */
class FreeTouch(val input: NativeBridge, val videoW: Int, val videoH: Int, val padCfg: PadConfig)

@Composable
fun VirtualPad(
    layout: PadLayout,
    haptics: Boolean,
    onState: (GamepadState) -> Unit,
    modifier: Modifier = Modifier,
    opacityScale: Float = 1f,
    freeTouch: FreeTouch? = null,
) {
    val view = LocalView.current
    val emit by rememberUpdatedState(onState)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val engine = remember(layout, w, h) { VirtualPadEngine(layout, w, h) }
        val free = remember(freeTouch, w, h) {
            freeTouch?.let { ft -> MirrorGestures({ VideoRect.fit(w, h, ft.videoW, ft.videoH) }, ft.padCfg) }
        }
        val freeIds = remember(engine) { mutableSetOf<Int>() }
        val shown = remember(layout, opacityScale) {
            layout.copy(controls = layout.controls.map { it.copy(opacity = (it.opacity * opacityScale).coerceIn(0.05f, 1f)) })
        }
        var state by remember(engine) { mutableStateOf(GamepadState()) }
        Box(
            Modifier.fillMaxSize().pointerInput(engine) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        for (c in ev.changes) {
                            val id = c.id.value.toInt()
                            val now = SystemClock.uptimeMillis()
                            val sample = TouchSample(id, c.position.x, c.position.y, now)
                            fun apply(a: List<PadAction>) = freeTouch?.let { ft -> a.forEach { applyMirror(ft.input, it, ft.videoW, ft.videoH) } }
                            when {
                                c.changedToDown() ->
                                    if (engine.down(id, c.position.x, c.position.y)) c.consume()
                                    else if (free != null) { freeIds += id; apply(free.down(sample)); c.consume() }
                                c.changedToUp() ->
                                    if (engine.owns(id)) { engine.up(id); c.consume() }
                                    else if (id in freeIds) { freeIds -= id; apply(free!!.up(sample)); c.consume() }
                                c.pressed && engine.owns(id) -> { engine.move(id, c.position.x, c.position.y); c.consume() }
                                c.pressed && id in freeIds -> { apply(free!!.move(sample)); c.consume() }
                            }
                        }
                        val s = engine.state()
                        if (s != state) {
                            if (haptics && (s.buttons and state.buttons.inv()) != 0) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            state = s
                            emit(s)
                        }
                    }
                }
            },
        ) {
            for (c in shown.controls) {
                val pressed = when (c.kind) {
                    ControlKind.BUTTON -> state.buttons and c.button != 0
                    ControlKind.TRIGGER_LEFT -> state.leftTrigger > 0
                    ControlKind.TRIGGER_RIGHT -> state.rightTrigger > 0
                    ControlKind.DPAD -> state.buttons and 0x000F != 0
                    else -> false
                }
                val knob = when (c.kind) {
                    ControlKind.STICK_LEFT -> state.lx / 32767f to -state.ly / 32767f
                    ControlKind.STICK_RIGHT -> state.rx / 32767f to -state.ry / 32767f
                    else -> 0f to 0f
                }
                PadControlView(c, w, h, pressed, knob, dpad = state.buttons and 0x000F)
            }
        }
    }
}

/**
 * Remote-mode mirroring input over the video: [TouchMode.MOUSE] runs [MirrorGestures] (tap = click
 * where touched), [TouchMode.NATIVE] forwards every finger as Windows touch. [onNativeUnsupported]
 * fires once if the host refuses native touch.
 */
@Composable
fun MirrorTouchLayer(
    input: NativeBridge,
    videoW: Int,
    videoH: Int,
    touchMode: TouchMode,
    padCfg: PadConfig,
    onNativeUnsupported: () -> Unit,
) {
    val unsupported by rememberUpdatedState(onNativeUnsupported)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rect = VideoRect.fit(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), videoW, videoH)
        val gestures = remember(rect, padCfg) { MirrorGestures({ rect }, padCfg) }
        Box(
            Modifier.fillMaxSize().pointerInput(rect, touchMode) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val now = SystemClock.uptimeMillis()
                        for (c in ev.changes) {
                            val id = c.id.value.toInt()
                            val p = c.position
                            if (touchMode == TouchMode.NATIVE) {
                                val (nx, ny) = rect.normalized(p.x, p.y)
                                val type = when {
                                    c.changedToDown() -> NativeBridge.TOUCH_DOWN
                                    c.changedToUp() -> NativeBridge.TOUCH_UP
                                    c.pressed -> NativeBridge.TOUCH_MOVE
                                    else -> continue
                                }
                                if (input.sendTouch(type, id, nx, ny) < 0) unsupported()
                            } else {
                                val s = TouchSample(id, p.x, p.y, now)
                                val actions = when {
                                    c.changedToDown() -> gestures.down(s)
                                    c.changedToUp() -> gestures.up(s)
                                    c.pressed -> gestures.move(s)
                                    else -> emptyList()
                                }
                                actions.forEach { applyMirror(input, it, videoW, videoH) }
                            }
                            c.consume()
                        }
                    }
                }
            },
        )
    }
}

private fun Int.clampShort(): Short = coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

internal fun applyMirror(input: NativeBridge, a: PadAction, w: Int, h: Int) {
    when (a) {
        is PadAction.PointTo -> input.sendMousePosition(a.x.clampShort(), a.y.clampShort(), w.clampShort(), h.clampShort())
        is PadAction.Click -> { input.sendMouseButton(true, a.button); input.sendMouseButton(false, a.button) }
        is PadAction.Press -> input.sendMouseButton(true, a.button)
        is PadAction.Release -> input.sendMouseButton(false, a.button)
        is PadAction.Scroll -> {
            if (a.v != 0) input.sendScroll(a.v.clampShort())
            if (a.h != 0) input.sendHScroll(a.h.clampShort())
        }
        is PadAction.Zoom -> {
            input.sendKey(Vk.CONTROL, true, NativeBridge.MOD_CTRL)
            input.sendScroll((a.steps * 120).clampShort())
            input.sendKey(Vk.CONTROL, false, NativeBridge.MOD_CTRL)
        }
        is PadAction.Move -> input.sendMouseMove(a.dx.clampShort(), a.dy.clampShort())
        is PadAction.Chord -> Unit
    }
}

/** The d-pad is just four arrows (no ring); the pressed directions light up. */
@Composable
private fun DpadArrows(c: PadControl, w: Float, h: Float, flags: Int, selected: Boolean) {
    val density = LocalDensity.current
    val d = c.size * h
    Box(
        Modifier
            .offset { IntOffset((c.x * w - d / 2).roundToInt(), (c.y * h - d / 2).roundToInt()) }
            .size(with(density) { d.toDp() }),
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val s = size.minDimension
            val cx = size.width / 2
            val cy = size.height / 2
            val reach = s * 0.42f   // distance of the arrow tips from the centre
            val half = s * 0.17f    // half width of the arrow base
            val base = s * 0.14f    // distance of the base from the centre
            fun arrow(dx: Float, dy: Float, on: Boolean) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    // tip, then two base corners
                    moveTo(cx + dx * reach, cy + dy * reach)
                    lineTo(cx + dx * base - dy * half, cy + dy * base + dx * half)
                    lineTo(cx + dx * base + dy * half, cy + dy * base - dx * half)
                    close()
                }
                val a = c.opacity * if (on) 0.95f else 0.45f
                drawPath(path, Color.White.copy(alpha = a))
                if (selected) drawPath(path, Color(0xFFFFC107), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
            }
            arrow(0f, -1f, flags and 0x0001 != 0) // up
            arrow(0f, 1f, flags and 0x0002 != 0)  // down
            arrow(-1f, 0f, flags and 0x0004 != 0) // left
            arrow(1f, 0f, flags and 0x0008 != 0)  // right
        }
    }
}
