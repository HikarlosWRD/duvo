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

import app.duvo.remote.stream.NativeBridge
import kotlin.math.abs
import kotlin.math.hypot

data class TouchSample(val pointerId: Int, val x: Float, val y: Float, val tMs: Long)

sealed interface PadAction {
    data class Move(val dx: Int, val dy: Int) : PadAction
    data class Click(val button: Int) : PadAction
    data class Press(val button: Int) : PadAction
    data class Release(val button: Int) : PadAction

    /** High-resolution wheel units (120 per notch); [v] < 0 = page scrolls down (finger down), [h] > 0 = page scrolls right (finger right), like the Windows precision touchpad default. */
    data class Scroll(val v: Int, val h: Int) : PadAction
    data class Chord(val vk: List<Short>, val modifiers: Byte) : PadAction

    /** Pinch zoom; positive = zoom in (fingers apart). The consumer emits Ctrl+wheel. */
    data class Zoom(val steps: Int) : PadAction

    /** Mirroring: put the cursor at ([x], [y]) in video pixels (absolute). */
    data class PointTo(val x: Int, val y: Int) : PadAction
}

/**
 * Touch-to-mouse gesture interpreter. Pure logic: time comes only from [TouchSample.tMs] and
 * [tick], never from the wall clock. Multi-finger decisions (scroll vs. pinch) are made on last-known pointer positions, so a pointer that does not report simply keeps its last position.
 */
class TrackpadGestures(private val cfg: PadConfig = PadConfig()) {
    private class Pointer(val startX: Float, val startY: Float, var x: Float, var y: Float, var t: Long) {
        var sentX = startX
        var sentY = startY
    }

    private enum class Mode { NONE, SCROLL, ZOOM }

    private val pointers = LinkedHashMap<Int, Pointer>()
    private var maxPointers = 0
    private var gestureStartT = 0L
    private var moved = false
    private var gestureLocked = false // Press emitted during this gesture
    private var suppressTap = false
    private var dragHeld = false // button held across gestures
    private var swipeFired = false
    private var mode = Mode.NONE
    private var centroidX = 0f
    private var centroidY = 0f
    private var scrollAccX = 0f
    private var scrollAccY = 0f
    private var spreadBase = 0f
    private var zoomAcc = 0f
    private var remX = 0f
    private var remY = 0f

    fun down(s: TouchSample): List<PadAction> {
        val out = ArrayList<PadAction>(1)
        if (pointers.isEmpty()) {
            maxPointers = 0
            gestureStartT = s.tMs
            moved = false
            gestureLocked = false
            suppressTap = false
            remX = 0f
            remY = 0f
            if (dragHeld) {
                dragHeld = false
                suppressTap = true
                out += PadAction.Release(NativeBridge.BUTTON_LEFT)
            }
        }
        pointers[s.pointerId] = Pointer(s.x, s.y, s.x, s.y, s.tMs)
        if (pointers.size > maxPointers) maxPointers = pointers.size
        if (pointers.size >= 2) resetMultiTouch()
        return out
    }

    fun move(s: TouchSample): List<PadAction> {
        val p = pointers[s.pointerId] ?: return emptyList()
        val out = ArrayList<PadAction>(2)
        out += tick(s.tMs)
        val dx = s.x - p.x
        val dy = s.y - p.y
        val dt = s.tMs - p.t
        p.x = s.x
        p.y = s.y
        p.t = s.tMs
        if (!moved && hypot(p.x - p.startX, p.y - p.startY) > cfg.tapSlopPx) moved = true

        when (pointers.size) {
            1 -> if (maxPointers == 1 && (moved || gestureLocked)) {
                val (fx, fy) = PointerCurve.applyRaw(p.x - p.sentX, p.y - p.sentY, dt, cfg)
                val tx = fx + remX
                val ty = fy + remY
                val mx = PointerCurve.roundAway(tx)
                val my = PointerCurve.roundAway(ty)
                remX = tx - mx
                remY = ty - my
                p.sentX = p.x
                p.sentY = p.y
                if (mx != 0 || my != 0) out += PadAction.Move(mx, my)
            }
            2 -> twoFingerMove(s, dx, dy, out)
            3 -> threeFingerMove(dx, dy, out)
        }
        return out
    }

    fun up(s: TouchSample): List<PadAction> {
        if (!pointers.containsKey(s.pointerId)) return emptyList()
        val out = ArrayList<PadAction>(2)
        out += tick(s.tMs)
        pointers.remove(s.pointerId)
        if (pointers.isEmpty()) {
            val tap = !moved && !gestureLocked && !suppressTap && !swipeFired && mode == Mode.NONE &&
                s.tMs - gestureStartT <= cfg.tapMaxMs
            if (tap) buttonForTap(maxPointers)?.let { out += PadAction.Click(it) }
            maxPointers = 0
        } else if (pointers.size >= 2) {
            resetMultiTouch()
        }
        return out
    }

    /**
     * Drops all touch state (ACTION_CANCEL, view detach, disconnect). Returns Release(LEFT) when a
     * drag-lock button is still held so the host button is never left stuck.
     */
    fun reset(): List<PadAction> {
        val release = dragHeld || gestureLocked
        pointers.clear()
        maxPointers = 0
        moved = false
        gestureLocked = false
        suppressTap = false
        dragHeld = false
        remX = 0f
        remY = 0f
        resetMultiTouch()
        return if (release) listOf(PadAction.Release(NativeBridge.BUTTON_LEFT)) else emptyList()
    }

    /** Poll (e.g. every 50 ms) so a held finger engages drag-lock without further touch events. */
    fun tick(nowMs: Long): List<PadAction> {
        if (pointers.size == 1 && maxPointers == 1 && !moved && !gestureLocked && !dragHeld &&
            nowMs - gestureStartT > cfg.dragLockMs
        ) {
            gestureLocked = true
            dragHeld = true
            return listOf(PadAction.Press(NativeBridge.BUTTON_LEFT))
        }
        return emptyList()
    }

    private fun buttonForTap(fingers: Int): Int? = when (fingers) {
        1 -> if (cfg.leftHanded) NativeBridge.BUTTON_RIGHT else NativeBridge.BUTTON_LEFT
        2 -> if (cfg.leftHanded) NativeBridge.BUTTON_LEFT else NativeBridge.BUTTON_RIGHT
        3 -> NativeBridge.BUTTON_MIDDLE
        else -> null
    }

    private fun resetMultiTouch() {
        mode = Mode.NONE
        centroidX = 0f
        centroidY = 0f
        scrollAccX = 0f
        scrollAccY = 0f
        zoomAcc = 0f
        swipeFired = false
        spreadBase = spread()
    }

    private fun spread(): Float {
        if (pointers.size < 2) return 0f
        val it = pointers.values.iterator()
        val a = it.next()
        val b = it.next()
        return hypot(a.x - b.x, a.y - b.y)
    }

    private fun twoFingerMove(s: TouchSample, dx: Float, dy: Float, out: MutableList<PadAction>) {
        centroidX += dx / 2f
        centroidY += dy / 2f
        scrollAccX += dx / 2f
        scrollAccY += dy / 2f
        val synced = true // decide on last-known positions; a pointer that did not report keeps its last one
        if (mode == Mode.NONE && synced) {
            val spreadDelta = spread() - spreadBase
            val travel = hypot(centroidX, centroidY)
            if (maxOf(abs(spreadDelta), travel) > cfg.tapSlopPx) {
                mode = if (abs(spreadDelta) > travel) Mode.ZOOM else Mode.SCROLL
                moved = true
            }
        }
        when (mode) {
            Mode.SCROLL -> {
                val v = (-scrollAccY / cfg.scrollPxPerNotch * NOTCH).toInt()
                val h = (scrollAccX / cfg.scrollPxPerNotch * NOTCH).toInt()
                scrollAccY += v * cfg.scrollPxPerNotch / NOTCH
                scrollAccX -= h * cfg.scrollPxPerNotch / NOTCH
                if (v != 0 || h != 0) out += PadAction.Scroll(v, h)
            }
            Mode.ZOOM -> if (synced) {
                val now = spread()
                zoomAcc += now - spreadBase
                spreadBase = now
                val steps = (zoomAcc / cfg.pinchPxPerStep).toInt()
                zoomAcc -= steps * cfg.pinchPxPerStep
                if (steps != 0) out += PadAction.Zoom(steps)
            }
            Mode.NONE -> Unit
        }
    }

    private fun threeFingerMove(dx: Float, dy: Float, out: MutableList<PadAction>) {
        centroidX += dx / 3f
        centroidY += dy / 3f
        if (swipeFired) return
        val ax = abs(centroidX)
        val ay = abs(centroidY)
        if (maxOf(ax, ay) < cfg.swipePx) return
        swipeFired = true
        moved = true
        out += if (ay >= ax) {
            if (centroidY < 0) PadAction.Chord(listOf(Vk.TAB), NativeBridge.MOD_META)
            else PadAction.Chord(listOf(Vk.D), NativeBridge.MOD_META)
        } else {
            if (centroidX < 0) PadAction.Chord(listOf(Vk.TAB), NativeBridge.MOD_ALT)
            else PadAction.Chord(
                listOf(Vk.TAB),
                (NativeBridge.MOD_ALT.toInt() or NativeBridge.MOD_SHIFT.toInt()).toByte(),
            )
        }
    }

    private companion object {
        const val NOTCH = 120f
    }
}
