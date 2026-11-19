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
package app.duvo.remote.gamepad

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Multi-touch to [GamepadState] for a [PadLayout] drawn on a [screenW]x[screenH] px area. Pure logic.
 * - A touch is captured by the control it starts on (sticks and d-pad keep it while it slides away).
 * - Buttons: a finger may slide from one button to another (like real face buttons); the hit area is 20% larger than drawn.
 * - Sticks: offset from the control center / radius, with a dead zone; d-pad: 8 directions.
 */
class VirtualPadEngine(
    private var layout: PadLayout,
    private var screenW: Float,
    private var screenH: Float,
    private val deadZone: Float = 0.12f,
) {
    private val captured = HashMap<Int, PadControl>()
    private val stickValues = HashMap<String, Pair<Float, Float>>()
    private val dpadDirs = HashMap<Int, Int>()

    fun setLayout(l: PadLayout, w: Float, h: Float) {
        layout = l; screenW = w; screenH = h
        reset()
    }

    fun reset() { captured.clear(); stickValues.clear(); dpadDirs.clear() }

    fun centerPx(c: PadControl) = c.x * screenW to c.y * screenH
    fun radiusPx(c: PadControl) = c.size * screenH / 2

    /** The control under ([x], [y]), topmost (last) first; null = empty area (the touch falls through). */
    fun hit(x: Float, y: Float): PadControl? = layout.controls.lastOrNull { c ->
        val (cx, cy) = centerPx(c)
        hypot(x - cx, y - cy) <= radiusPx(c) * HIT_SLACK
    }

    /** Returns whether the pad took the touch. */
    fun down(id: Int, x: Float, y: Float): Boolean {
        val c = hit(x, y) ?: return false
        captured[id] = c
        apply(id, c, x, y)
        return true
    }

    fun move(id: Int, x: Float, y: Float) {
        val c = captured[id] ?: return
        if (c.kind == ControlKind.BUTTON) {
            val over = hit(x, y)
            if (over != null && over.kind == ControlKind.BUTTON) captured[id] = over
            return
        }
        apply(id, c, x, y)
    }

    fun up(id: Int) {
        val c = captured.remove(id) ?: return
        if (c.kind == ControlKind.STICK_LEFT || c.kind == ControlKind.STICK_RIGHT) stickValues.remove(c.id)
        dpadDirs.remove(id)
    }

    fun owns(id: Int) = id in captured

    private fun apply(id: Int, c: PadControl, x: Float, y: Float) {
        val (cx, cy) = centerPx(c)
        val r = radiusPx(c).coerceAtLeast(1f)
        when (c.kind) {
            ControlKind.STICK_LEFT, ControlKind.STICK_RIGHT -> {
                var nx = (x - cx) / r
                var ny = (y - cy) / r
                val len = hypot(nx, ny)
                if (len > 1f) { nx /= len; ny /= len }
                if (len < deadZone) { nx = 0f; ny = 0f } else {
                    // Rescale past the dead zone so the stick still reaches full range smoothly.
                    val k = ((minOf(len, 1f) - deadZone) / (1f - deadZone)) / minOf(len, 1f)
                    nx *= k; ny *= k
                }
                stickValues[c.id] = nx to ny
            }
            ControlKind.DPAD -> {
                val dx = x - cx
                val dy = y - cy
                dpadDirs[id] = if (hypot(dx, dy) < r * 0.2f) 0 else dirFlags(atan2(-dy, dx).toDouble())
            }
            else -> Unit
        }
    }

    fun state(): GamepadState {
        var buttons = 0
        var lt = 0
        var rt = 0
        for ((id, c) in captured) when (c.kind) {
            ControlKind.BUTTON -> buttons = buttons or c.button
            ControlKind.TRIGGER_LEFT -> lt = 255
            ControlKind.TRIGGER_RIGHT -> rt = 255
            ControlKind.DPAD -> buttons = buttons or (dpadDirs[id] ?: 0)
            else -> Unit
        }
        val l = layout.controls.firstOrNull { it.kind == ControlKind.STICK_LEFT }?.let { stickValues[it.id] } ?: (0f to 0f)
        val r = layout.controls.firstOrNull { it.kind == ControlKind.STICK_RIGHT }?.let { stickValues[it.id] } ?: (0f to 0f)
        return GamepadState(buttons, lt, rt, axis(l.first), axis(-l.second), axis(r.first), axis(-r.second))
    }

    private fun axis(v: Float): Short = (v * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()

    companion object {
        private const val HIT_SLACK = 1.2f

        /** 8-way d-pad from an angle (radians, 0 = right, counter-clockwise, y up). */
        fun dirFlags(angle: Double): Int {
            val sector = ((Math.toDegrees(angle) + 360 + 22.5) % 360 / 45).toInt()
            return when (sector) {
                0 -> Btn.RIGHT
                1 -> Btn.RIGHT or Btn.UP
                2 -> Btn.UP
                3 -> Btn.UP or Btn.LEFT
                4 -> Btn.LEFT
                5 -> Btn.LEFT or Btn.DOWN
                6 -> Btn.DOWN
                else -> Btn.DOWN or Btn.RIGHT
            }
        }
    }
}
