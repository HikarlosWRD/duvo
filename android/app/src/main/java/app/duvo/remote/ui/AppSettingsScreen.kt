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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.duvo.remote.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.duvo.remote.R
import app.duvo.remote.ui.theme.Appearance
import app.duvo.remote.ui.theme.GroupDivider
import app.duvo.remote.ui.theme.IconTile
import app.duvo.remote.ui.theme.SectionLabel
import app.duvo.remote.ui.theme.Segmented
import app.duvo.remote.ui.theme.SettingsGroup
import app.duvo.remote.ui.theme.SettingsRow

/** App-wide settings: appearance and the entries to the other settings screens. */
@Composable
fun AppSettingsScreen(
    appearance: kotlinx.coroutines.flow.StateFlow<Appearance>,
    onAppearance: (Appearance) -> Unit,
    onBack: () -> Unit,
    onStreaming: () -> Unit,
    onPadEditor: () -> Unit,
    onProbe: () -> Unit,
) {
    val current by appearance.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateStatus by remember { mutableStateOf("Toque para verificar") }
    val versionName = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconTile(R.drawable.ic_back, "Voltar", onBack, outlined = false)
            Text("Configurações", style = MaterialTheme.typography.headlineSmall)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Aparência")
            Segmented(Appearance.values().map { it to it.label }, current, onAppearance)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Ajustes")
            SettingsGroup {
                SettingsRow("Transmissão", subtitle = "Resolução, taxa de bits, codec e som", onClick = onStreaming)
                GroupDivider()
                SettingsRow("Controle virtual", subtitle = "Posição e tamanho dos botões do Gaming", onClick = onPadEditor)
                GroupDivider()
                SettingsRow("Sonda do decoder", subtitle = "O que este aparelho consegue decodificar", onClick = onProbe)
            }
        }
    }
}
