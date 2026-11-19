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

package app.duvo.remote.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.selects.select

sealed interface Raced<out T> {
    /** The probe found a network problem first (or the attempt failed and the probe explains why). */
    data class Diagnosed(val diagnosis: Diagnosis) : Raced<Nothing>
    data class Completed<T>(val value: T) : Raced<T>
}

/**
 * Runs [attempt] and [diagnose] in parallel. A non-OK diagnosis wins at once and the attempt is cancelled
 * (best effort: a blocking call keeps running detached, its late result is ignored). An OK diagnosis lets
 * the attempt report its own result. If the attempt ends in a failure ([failed] or an exception) the
 * diagnosis is awaited and, when non-OK, replaces the failure.
 */
suspend fun <T> raceWithDiagnosis(
    attempt: suspend () -> T,
    diagnose: suspend () -> Diagnosis,
    failed: (T) -> Boolean = { false },
): Raced<T> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        val a = scope.async { runCatching { attempt() } }
        val d = scope.async { runCatching { diagnose() }.getOrDefault(Diagnosis.OK) }
        val attemptFirst = select<Boolean> {
            a.onAwait { true }
            d.onAwait { false }
        }
        if (!attemptFirst) {
            val dg = d.await()
            if (dg != Diagnosis.OK) return Raced.Diagnosed(dg)
        }
        val r = a.await()
        if (r.isSuccess && !failed(r.getOrThrow())) return Raced.Completed(r.getOrThrow())
        val dg = d.await()
        if (dg != Diagnosis.OK) return Raced.Diagnosed(dg)
        return Raced.Completed(r.getOrThrow())
    } finally {
        scope.cancel()
    }
}
