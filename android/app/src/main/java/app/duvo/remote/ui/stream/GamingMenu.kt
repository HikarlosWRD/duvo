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
package app.duvo.remote.ui.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.duvo.remote.DuvoApp
import app.duvo.remote.stream.StreamMode
import app.duvo.remote.ui.theme.GroupDivider
import app.duvo.remote.ui.theme.PrimaryButton
import app.duvo.remote.ui.theme.QuietButton
import app.duvo.remote.ui.theme.SectionLabel
import app.duvo.remote.ui.theme.SettingsGroup
import app.duvo.remote.ui.theme.SwitchRow

/**
 * In-game menu: the screen darkens and a panel slides in from the left. Every change is saved as the app's general
 * setting right away; resolution, bitrate, codec and the like take effect when the stream is restarted ([onApply]).
 */
@Composable
fun GamingMenu(mode: StreamMode, withVideo: Boolean, onClose: () -> Unit, onApply: () -> Unit, actions: @Composable () -> Unit = {}) {
    val duvo = LocalContext.current.applicationContext as DuvoApp
    val opacity by duvo.uiPrefs.padOpacity.collectAsState()
    val through by duvo.uiPrefs.touchThrough.collectAsState()
    var s by remember { mutableStateOf(duvo.streamPrefs.get(mode)) }
    var changed by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(onClick = onClose))
        Column(
            Modifier
                .fillMaxHeight()
                .width(380.dp)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(if (mode == StreamMode.GAMING) "Ajustes do jogo" else "Ajustes", style = MaterialTheme.typography.titleLarge)

            actions()

            if (mode == StreamMode.GAMING) SectionLabel("Botões na tela")
            if (mode == StreamMode.GAMING) SettingsGroup {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Opacidade de todos os botões", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("${(opacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Slider(value = opacity, onValueChange = duvo.uiPrefs::setPadOpacity, valueRange = 0.2f..1f)
                }
                if (withVideo) {
                    GroupDivider()
                    SwitchRow(
                        "Tocar na tela para usar o PC",
                        through,
                        duvo.uiPrefs::setTouchThrough,
                        subtitle = "Toques fora dos botões viram mouse, para abrir o jogo sem sair daqui",
                    )
                }
            }

            if (withVideo) {
                SectionLabel("Transmissão")
                StreamSettingsFields(mode, s) { n -> s = n; duvo.streamPrefs.set(mode, n); changed = true }
                if (changed) {
                    Text(
                        "Resolução, taxa de bits, codec e som mudam ao reconectar. Está salvo como padrão do app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrimaryButton("Aplicar e reconectar", onApply)
                }
            }
        }
    }
}
