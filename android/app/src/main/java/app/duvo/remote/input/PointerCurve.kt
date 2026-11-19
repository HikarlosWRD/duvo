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

import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Trackpad tuning. Timings are in sample milliseconds, distances in px. */
data class PadConfig(
    val sensitivity: Float = 1.0f,
    val acceleration: Boolean = true,
    val leftHanded: Boolean = false,
    val tapSlopPx: Float = 8f,
    val tapMaxMs: Long = 200,
    val dragLockMs: Long = 1000,
    /** Finger travel (px) of a two-finger scroll that equals one wheel notch (120 units). */
    val scrollPxPerNotch: Float = 60f,
    /** Change in finger spread (px) of a pinch that equals one [PadAction.Zoom] step. */
    val pinchPxPerStep: Float = 40f,
    /** Dominant-axis travel (px) that triggers a three-finger swipe. */
    val swipePx: Float = 80f,
)

object PointerCurve {
    private const val ACCEL_PER_PX_MS = 0.5f
    private const val MAX_GAIN = 6f

    /** Maps a finger delta ([dx],[dy] px over [dtMs]) to a cursor delta in host pixels. */
    fun apply(dx: Float, dy: Float, dtMs: Long, cfg: PadConfig): Pair<Int, Int> {
        val (x, y) = applyRaw(dx, dy, dtMs, cfg)
        return Pair(roundAway(x), roundAway(y))
    }

    /** Same curve without rounding, so callers can carry the fractional remainder. */
    fun applyRaw(dx: Float, dy: Float, dtMs: Long, cfg: PadConfig): Pair<Float, Float> {
        var gain = cfg.sensitivity
        if (cfg.acceleration) {
            val speed = hypot(dx, dy) / maxOf(dtMs, 1L) // px per ms
            gain *= min(1f + ACCEL_PER_PX_MS * speed, MAX_GAIN)
        }
        return Pair(dx * gain, dy * gain)
    }

    /** Round half away from zero (symmetric). */
    fun roundAway(v: Float): Int = if (v < 0) -((-v).roundToInt()) else v.roundToInt()
}
