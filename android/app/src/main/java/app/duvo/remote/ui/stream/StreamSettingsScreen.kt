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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.duvo.remote.DuvoApp
import app.duvo.remote.stream.AudioOut
import app.duvo.remote.stream.Codec
import app.duvo.remote.stream.HostScreen
import app.duvo.remote.stream.StreamMode
import app.duvo.remote.stream.StreamSettings
import app.duvo.remote.stream.TouchMode
import kotlin.math.roundToInt

/** Streaming settings per mode (spec §8.3.2/§8.4); saved on every change. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StreamSettingsScreen(onBack: () -> Unit) {
    val prefs = (LocalContext.current.applicationContext as DuvoApp).streamPrefs
    var mode by remember { mutableStateOf(StreamMode.GAMING) }
    var s by remember(mode) { mutableStateOf(prefs.get(mode)) }
    fun update(n: StreamSettings) { s = n; prefs.set(mode, n) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            app.duvo.remote.ui.theme.IconTile(app.duvo.remote.R.drawable.ic_back, "Voltar", onBack, outlined = false)
            Text("Transmissão", style = MaterialTheme.typography.headlineSmall)
        }
        app.duvo.remote.ui.theme.Segmented(
            listOf(StreamMode.GAMING to "Gaming", StreamMode.REMOTE to "Espelhar", StreamMode.MONITOR to "Monitor"),
            mode,
            onSelect = { mode = it },
        )

        StreamSettingsFields(mode, s, ::update)
    }
}

/** The setting rows for one mode; [update] is called with the whole new settings on every change. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StreamSettingsFields(mode: StreamMode, s: StreamSettings, update: (StreamSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Resolução")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = s.width == null, onClick = { update(s.copy(width = null, height = null)) }, label = { Text("Tela do aparelho") })
            StreamSettings.RESOLUTIONS.forEach { (w, h) ->
                FilterChip(selected = s.width == w && s.height == h, onClick = { update(s.copy(width = w, height = h)) }, label = { Text("${h}p") })
            }
        }
        Text("Acima da tela do aparelho a imagem fica mais nítida, mas pesa mais no decoder.", style = MaterialTheme.typography.bodySmall)

        Section("Quadros por segundo")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = s.fps == null, onClick = { update(s.copy(fps = null)) }, label = { Text("Automático") })
            StreamSettings.FPS_CHOICES.forEach { f ->
                FilterChip(selected = s.fps == f, onClick = { update(s.copy(fps = f)) }, label = { Text("$f") })
            }
        }

        Section("Taxa de bits")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Automática", modifier = Modifier.weight(1f))
            Switch(checked = s.bitrateKbps == null, onCheckedChange = { auto -> update(s.copy(bitrateKbps = if (auto) null else 20_000)) })
        }
        s.bitrateKbps?.let { kbps ->
            Text("${kbps / 1000} Mbps")
            Slider(
                value = kbps / 1000f,
                onValueChange = { update(s.copy(bitrateKbps = (it.roundToInt() * 1000))) },
                valueRange = 1f..150f,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Section("Codec")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Codec.values().forEach { c -> FilterChip(selected = s.codec == c, onClick = { update(s.copy(codec = c)) }, label = { Text(c.label) }) }
        }

        Section("Tela do PC transmitida")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HostScreen.values().forEach { v -> FilterChip(selected = s.screen == v, onClick = { update(s.copy(screen = v)) }, label = { Text(v.label) }) }
        }
        Text("A tela virtual tem o tamanho do aparelho e some ao sair; o monitor principal é o que você vê no PC.", style = MaterialTheme.typography.bodySmall)

        Section("Som")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AudioOut.values().forEach { a -> FilterChip(selected = s.audio == a, onClick = { update(s.copy(audio = a)) }, label = { Text(a.label) }) }
        }

        if (mode != StreamMode.GAMING) {
            Section("Toque no espelhamento")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TouchMode.values().forEach { t -> FilterChip(selected = s.touchMode == t, onClick = { update(s.copy(touchMode = t)) }, label = { Text(t.label) }) }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mostrar estatísticas ao iniciar", modifier = Modifier.weight(1f))
            Switch(checked = s.showStats, onCheckedChange = { update(s.copy(showStats = it)) })
        }
        TextButton(onClick = { update(StreamSettings.defaultsFor(mode)) }) { Text("Restaurar padrões deste modo") }
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleSmall)

private val TABS = listOf(StreamMode.GAMING, StreamMode.REMOTE, StreamMode.MONITOR)
