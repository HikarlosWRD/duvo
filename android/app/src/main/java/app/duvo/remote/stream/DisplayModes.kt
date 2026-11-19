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

import android.view.Display

/** One [Display.Mode], as plain data so the choice logic runs on the JVM. Sizes are physical pixels. */
data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshHz: Float) {
    fun sameResolution(o: DisplayModeInfo) = width == o.width && height == o.height
}

object DisplayModes {
    /** Highest refresh rate among [supported] modes with the [current] mode's resolution (R12b). */
    fun maxRefreshHz(current: DisplayModeInfo, supported: List<DisplayModeInfo>): Float =
        supported.filter { it.sameResolution(current) }.maxOfOrNull { it.refreshHz }
            ?.coerceAtLeast(current.refreshHz) ?: current.refreshHz

    /**
     * The mode (same resolution as [current]) to run a [fps] stream on: one whose refresh rate is
     * that fps (57.99 Hz counts as 60), else the lowest integer multiple of it, else the fastest.
     */
    fun modeForFps(current: DisplayModeInfo, supported: List<DisplayModeInfo>, fps: Int): DisplayModeInfo {
        val same = (supported.filter { it.sameResolution(current) } + current).distinctBy { it.id }
        same.filter { matches(it.refreshHz, fps) }.minByOrNull { kotlin.math.abs(it.refreshHz - fps) }?.let { return it }
        same.filter { m -> (2..4).any { matches(m.refreshHz, fps * it) } }.minByOrNull { it.refreshHz }?.let { return it }
        return same.maxBy { it.refreshHz }
    }

    /** Same tolerance as [DecoderProbe]'s refresh snapping: [fps - 3, fps + 1]. */
    private fun matches(hz: Float, fps: Int) = hz >= fps - 3 && hz <= fps + 1

    fun current(display: Display): DisplayModeInfo = display.mode.toInfo()

    fun supported(display: Display): List<DisplayModeInfo> = display.supportedModes.map { it.toInfo() }

    private fun Display.Mode.toInfo() = DisplayModeInfo(modeId, physicalWidth, physicalHeight, refreshRate)
}
