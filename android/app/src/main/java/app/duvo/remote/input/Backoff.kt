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

/** Reconnection delays: 1, 2, 4, 8, 16, then 30 s for every later attempt. */
object Backoff {
    const val FIRST_MS = 1_000L
    const val MAX_MS = 30_000L

    /** Delay before reconnection [attempt] (1-based; anything below 1 counts as the first). */
    fun delayMs(attempt: Int): Long {
        val n = attempt.coerceIn(1, 6) // 2^5 s = 32 s is already past the cap
        return minOf(FIRST_MS shl (n - 1), MAX_MS)
    }
}
