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
package app.duvo.remote.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-wide UI choices that are not tied to one streaming mode: pad opacity and touch-through. */
class UiPrefs(context: Context) {
    private val sp = context.getSharedPreferences("duvo_ui", Context.MODE_PRIVATE)
    private val _padOpacity = MutableStateFlow(sp.getFloat("padOpacity", 1f).coerceIn(0.2f, 1f))
    private val _touchThrough = MutableStateFlow(sp.getBoolean("touchThrough", false))

    /** Multiplies the opacity of every on-screen pad control (1 = as in the layout). */
    val padOpacity: StateFlow<Float> = _padOpacity.asStateFlow()

    /** Gaming with the PC picture: touches outside the pad act on the PC (mouse). */
    val touchThrough: StateFlow<Boolean> = _touchThrough.asStateFlow()

    fun setPadOpacity(v: Float) {
        val x = v.coerceIn(0.2f, 1f)
        _padOpacity.value = x
        sp.edit().putFloat("padOpacity", x).apply()
    }

    fun setTouchThrough(on: Boolean) {
        _touchThrough.value = on
        sp.edit().putBoolean("touchThrough", on).apply()
    }
}
