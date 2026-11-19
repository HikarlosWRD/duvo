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
package app.duvo.remote.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.duvo.remote.input.PadConfig
import app.duvo.remote.input.PanelId

/** pt-BR tab name of a panel. */
fun panelLabel(p: PanelId): String = when (p) {
    PanelId.KEYBOARD -> "Teclado"
    PanelId.MEDIA -> "Mídia"
    PanelId.BROWSER -> "Navegador"
    PanelId.PRESENTATION -> "Apresentação"
    PanelId.APPS -> "Apps"
    PanelId.POWER -> "Energia"
}

/** Remote Control settings: trackpad, feedback, phone buttons, clipboard and the panel editor. */
@Composable
fun RemoteSettingsScreen(
    settings: RemoteSettings,
    onPadConfig: (PadConfig) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onTapSound: (Boolean) -> Unit,
    onVolumeButtons: (Boolean) -> Unit,
    onAutoClipboard: (Boolean) -> Unit,
    onMovePanel: (PanelId, Int) -> Unit,
    onPanelHidden: (PanelId, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val pad = settings.pad
    // Slider moves locally; saved (and the gesture interpreter rebuilt) when the finger lifts.
    var sensitivity by remember(pad.sensitivity) { mutableFloatStateOf(pad.sensitivity) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Configurações do controle", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Voltar") }
        }

        Text("Trackpad", style = MaterialTheme.typography.titleMedium)
        Text("Sensibilidade: %.2fx".format(sensitivity))
        Slider(
            value = sensitivity,
            onValueChange = { sensitivity = it },
            onValueChangeFinished = { onPadConfig(pad.copy(sensitivity = sensitivity)) },
            valueRange = 0.25f..4f,
        )
        SwitchRow("Aceleração do ponteiro", pad.acceleration) { onPadConfig(pad.copy(acceleration = it)) }
        SwitchRow("Modo canhoto (inverte os botões)", pad.leftHanded) { onPadConfig(pad.copy(leftHanded = it)) }

        HorizontalDivider()
        Text("Retorno ao tocar", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Vibração", settings.haptics, onHaptics)
        SwitchRow("Som de toque", settings.tapSound, onTapSound)

        HorizontalDivider()
        Text("Celular", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Botões de volume do celular controlam o volume do PC", settings.volumeButtonsControlPc, onVolumeButtons)
        SwitchRow("Clipboard automático ao voltar ao app", settings.autoClipboardOnResume, onAutoClipboard)

        HorizontalDivider()
        Text("Editar painéis", style = MaterialTheme.typography.titleMedium)
        settings.panelOrder.forEachIndexed { i, p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(panelLabel(p), modifier = Modifier.weight(1f))
                TextButton(enabled = i > 0, onClick = { onMovePanel(p, -1) }) { Text("Subir") }
                TextButton(enabled = i < settings.panelOrder.lastIndex, onClick = { onMovePanel(p, +1) }) { Text("Descer") }
                Switch(checked = p !in settings.hidden, onCheckedChange = { onPanelHidden(p, !it) })
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
