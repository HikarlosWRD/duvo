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

enum class PanelId { KEYBOARD, MEDIA, BROWSER, PRESENTATION, APPS, POWER }

/** A one-tap key chord: [keys] pressed together under [modifiers] (NativeBridge MOD_* mask). */
data class PanelAction(val id: String, val label: String, val keys: List<Short>, val modifiers: Byte)

object Panels {
    private const val NONE: Byte = 0
    private const val C = NativeBridge.MOD_CTRL
    private val CS: Byte = (NativeBridge.MOD_CTRL.toInt() or NativeBridge.MOD_SHIFT.toInt()).toByte()

    private fun a(id: String, label: String, key: Short, mods: Byte = NONE) = PanelAction(id, label, listOf(key), mods)

    private val MEDIA = listOf(
        a("play_pause", "Reproduzir/Pausar", Vk.MEDIA_PLAY_PAUSE),
        a("next", "Próxima", Vk.MEDIA_NEXT),
        a("prev", "Anterior", Vk.MEDIA_PREV),
        a("stop", "Parar", Vk.MEDIA_STOP),
        a("vol_up", "Aumentar volume", Vk.VOL_UP),
        a("vol_down", "Diminuir volume", Vk.VOL_DOWN),
        a("mute", "Mudo", Vk.VOL_MUTE),
    )

    private val BROWSER = listOf(
        a("back", "Voltar", Vk.LEFT, NativeBridge.MOD_ALT),
        a("forward", "Avançar", Vk.RIGHT, NativeBridge.MOD_ALT),
        a("refresh", "Atualizar", Vk.F5),
        a("new_tab", "Nova aba", Vk.T, C),
        a("close_tab", "Fechar aba", Vk.W, C),
        a("reopen_tab", "Reabrir aba", Vk.T, CS),
        a("next_tab", "Próxima aba", Vk.TAB, C),
        a("prev_tab", "Aba anterior", Vk.TAB, CS),
        a("zoom_in", "Ampliar", Vk.OEM_PLUS, C),
        a("zoom_out", "Reduzir", Vk.OEM_MINUS, C),
        a("zoom_reset", "Zoom padrão", Vk.N0, C),
        a("address", "Barra de endereço", Vk.L, C),
        a("fullscreen", "Tela cheia", Vk.F11),
    )

    private val PRESENTATION = listOf(
        a("next", "Próximo slide", Vk.PGDN),
        a("prev", "Slide anterior", Vk.PGUP),
        a("start", "Iniciar", Vk.F5),
        a("start_current", "Iniciar do slide atual", Vk.F5, NativeBridge.MOD_SHIFT),
        a("exit", "Sair", Vk.ESC),
        a("black", "Tela preta", Vk.B),
    )

    /** Key-chord actions of [p]; panels without chord buttons (keyboard, apps, power) are empty. */
    fun actions(p: PanelId): List<PanelAction> = when (p) {
        PanelId.MEDIA -> MEDIA
        PanelId.BROWSER -> BROWSER
        PanelId.PRESENTATION -> PRESENTATION
        else -> emptyList()
    }
}
