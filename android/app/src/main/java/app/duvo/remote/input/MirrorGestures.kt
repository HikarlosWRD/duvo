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

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Where the video is drawn inside the view (letterboxed, aspect kept). */
data class VideoRect(val left: Float, val top: Float, val width: Float, val height: Float, val videoW: Int, val videoH: Int) {
    /** View point to video pixel, clamped to the picture. */
    fun toVideo(x: Float, y: Float): Pair<Int, Int> {
        val (nx, ny) = normalized(x, y)
        return (nx * (videoW - 1)).roundToInt() to (ny * (videoH - 1)).roundToInt()
    }

    /** View point to 0..1 inside the picture (for native touch). */
    fun normalized(x: Float, y: Float): Pair<Float, Float> =
        ((x - left) / width).coerceIn(0f, 1f) to ((y - top) / height).coerceIn(0f, 1f)

    companion object {
        fun fit(viewW: Float, viewH: Float, videoW: Int, videoH: Int): VideoRect {
            val scale = minOf(viewW / videoW, viewH / videoH)
            val w = videoW * scale
            val h = videoH * scale
            return VideoRect((viewW - w) / 2, (viewH - h) / 2, w, h, videoW, videoH)
        }
    }
}

/**
 * Mirroring in "mouse" mode, where the phone screen is the PC screen:
 * - tap = left click where touched; hold without moving = right click; touch and drag = left drag;
 * - two fingers = scroll (content follows the fingers); pinch = zoom (Ctrl+wheel); two-finger tap = right click.
 * Pure logic, like [TrackpadGestures]: time comes only from [TouchSample.tMs].
 */
class MirrorGestures(
    private val rect: () -> VideoRect,
    private val cfg: PadConfig = PadConfig(),
    private val longPressMs: Long = 500,
) {
    private val pts = LinkedHashMap<Int, Pair<Float, Float>>()
    private var downAt = 0L
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var maxFingers = 0
    private var multiMoved = false
    private var pinching = false
    private var lastCx = 0f
    private var lastCy = 0f
    private var lastSpread = 0f
    private var scrollAccV = 0f
    private var scrollAccH = 0f
    private var zoomAcc = 0f

    fun down(s: TouchSample): List<PadAction> {
        val out = mutableListOf<PadAction>()
        pts[s.pointerId] = s.x to s.y
        if (pts.size == 1) {
            downAt = s.tMs; startX = s.x; startY = s.y
            dragging = false; multiMoved = false; pinching = false
        } else if (pts.size == 2) {
            if (dragging) { out += PadAction.Release(BUTTON_LEFT); dragging = false }
            val (cx, cy) = centroid()
            lastCx = cx; lastCy = cy; lastSpread = spread()
            scrollAccV = 0f; scrollAccH = 0f; zoomAcc = 0f
        }
        maxFingers = maxOf(maxFingers, pts.size)
        return out
    }

    fun move(s: TouchSample): List<PadAction> {
        if (s.pointerId !in pts) return emptyList()
        pts[s.pointerId] = s.x to s.y
        val out = mutableListOf<PadAction>()
        if (pts.size == 1 && maxFingers == 1) {
            if (!dragging && abs(s.x - startX) + abs(s.y - startY) > cfg.tapSlopPx) {
                dragging = true
                out += point(startX, startY)
                out += PadAction.Press(BUTTON_LEFT)
            }
            if (dragging) out += point(s.x, s.y)
        } else if (pts.size >= 2) {
            twoFinger(out)
        }
        return out
    }

    fun up(s: TouchSample): List<PadAction> {
        if (s.pointerId !in pts) return emptyList()
        val last = pts.size == 1
        pts.remove(s.pointerId)
        if (!last) return emptyList()
        val out = mutableListOf<PadAction>()
        val held = s.tMs - downAt
        when {
            dragging -> { out += point(s.x, s.y); out += PadAction.Release(BUTTON_LEFT) }
            maxFingers == 2 && !multiMoved && held <= cfg.tapMaxMs * 2 -> {
                out += point(lastCx, lastCy); out += PadAction.Click(BUTTON_RIGHT)
            }
            maxFingers == 1 && held >= longPressMs -> { out += point(startX, startY); out += PadAction.Click(BUTTON_RIGHT) }
            maxFingers == 1 -> { out += point(startX, startY); out += PadAction.Click(BUTTON_LEFT) }
        }
        dragging = false
        maxFingers = 0
        return out
    }

    /** Touch stream cancelled (focus loss): release a held button. */
    fun reset(): List<PadAction> {
        val out = if (dragging) listOf<PadAction>(PadAction.Release(BUTTON_LEFT)) else emptyList()
        pts.clear(); dragging = false; maxFingers = 0
        return out
    }

    private fun twoFinger(out: MutableList<PadAction>) {
        val (cx, cy) = centroid()
        val sp = spread()
        val dSpread = sp - lastSpread
        val dx = cx - lastCx
        val dy = cy - lastCy
        lastCx = cx; lastCy = cy; lastSpread = sp
        if (!pinching && abs(dSpread) > 1f && abs(dSpread) > hypot(dx, dy)) pinching = true
        if (abs(dx) + abs(dy) + abs(dSpread) > 0.5f) multiMoved = true
        if (pinching) {
            zoomAcc += dSpread
            val steps = (zoomAcc / cfg.pinchPxPerStep).toInt()
            if (steps != 0) { out += PadAction.Zoom(steps); zoomAcc -= steps * cfg.pinchPxPerStep }
            return
        }
        scrollAccV += dy
        scrollAccH += dx
        // Content follows the fingers, like a touchscreen: finger down = wheel up (positive), finger right = scroll left.
        val v = (scrollAccV / cfg.scrollPxPerNotch * NOTCH).toInt()
        val h = (-scrollAccH / cfg.scrollPxPerNotch * NOTCH).toInt()
        if (v != 0 || h != 0) {
            out += PadAction.Scroll(v, h)
            scrollAccV -= v * cfg.scrollPxPerNotch / NOTCH
            scrollAccH += h * cfg.scrollPxPerNotch / NOTCH
        }
    }

    private fun point(x: Float, y: Float): PadAction {
        val (vx, vy) = rect().toVideo(x, y)
        return PadAction.PointTo(vx, vy)
    }

    private fun centroid(): Pair<Float, Float> {
        val two = pts.values.take(2)
        return (two[0].first + two[1].first) / 2 to (two[0].second + two[1].second) / 2
    }

    private fun spread(): Float {
        val two = pts.values.take(2)
        return hypot(two[0].first - two[1].first, two[0].second - two[1].second)
    }

    companion object {
        const val BUTTON_LEFT = 1
        const val BUTTON_RIGHT = 3
        private const val NOTCH = 120
    }
}
