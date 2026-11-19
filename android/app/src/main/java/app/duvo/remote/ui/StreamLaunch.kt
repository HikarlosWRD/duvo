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

package app.duvo.remote.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val STREAM_INTERRUPTED_MESSAGE =
    "A conexão foi interrompida porque o Duvo saiu da tela. Toque em Voltar e tente de novo."

/** Launch job and UI state of [StreamViewModel], kept apart so the lifecycle rules are JVM-testable. */
class StreamLaunch(private val scope: CoroutineScope) {
    private val _ui = MutableStateFlow<StreamUi>(StreamUi.Launching)
    val ui: StateFlow<StreamUi> = _ui.asStateFlow()

    private var job: Job? = null
    private var stopped = false

    /** False once a launch ran or the screen was stopped: one launch per screen. */
    val canBegin: Boolean get() = job == null && !stopped

    fun set(ui: StreamUi) { _ui.value = ui }

    /** Runs [block] in the scope unless [canBegin] is false; returns whether it started. */
    fun begin(block: suspend CoroutineScope.() -> Unit): Boolean {
        if (!canBegin) return false
        job = scope.launch(block = block)
        return true
    }

    /**
     * Cancels the launch (the surface is gone). If nothing was shown yet the screen would spin forever
     * on Launching, since a later surfaceCreated cannot start over (the host may already be launching
     * the session): show an error with "Voltar" instead.
     */
    fun stop() {
        stopped = true
        job?.cancel()
        if (_ui.value == StreamUi.Launching) _ui.value = StreamUi.Error(STREAM_INTERRUPTED_MESSAGE)
    }
}
