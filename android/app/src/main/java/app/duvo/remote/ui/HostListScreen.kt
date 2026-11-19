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

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.duvo.remote.R
import app.duvo.remote.discovery.DiscoveredHost
import app.duvo.remote.discovery.HostDiscovery
import app.duvo.remote.net.ConnectionDiagnostics
import app.duvo.remote.net.Diagnosis
import app.duvo.remote.net.message
import app.duvo.remote.pairing.QrPayload
import app.duvo.remote.stream.StreamMode
import app.duvo.remote.ui.theme.Duvo
import app.duvo.remote.ui.theme.IconTile
import app.duvo.remote.ui.theme.ModeTile
import app.duvo.remote.ui.theme.PlexMono
import app.duvo.remote.ui.theme.PrimaryButton
import app.duvo.remote.ui.theme.StatusDot
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The brand mark: monitor outline in the text colour, amber phone in front. */
@Composable
fun DuvoMark(size: androidx.compose.ui.unit.Dp = 28.dp) {
    Box(Modifier.size(size)) {
        Icon(painterResource(R.drawable.ic_mark_outline), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(size))
        Icon(painterResource(R.drawable.ic_mark_phone), contentDescription = null, tint = Duvo.Amber, modifier = Modifier.size(size))
    }
}

@Composable
fun HostListScreen(
    discovery: HostDiscovery,
    onScanQr: () -> Unit,
    /** Remote Control mode for a paired host (its uniqueid). */
    onOpenRemote: (String) -> Unit,
    /** Desktop streaming: mirror, monitor or gaming (with or without video). */
    onOpenStream: (DiscoveredHost, StreamMode, Boolean) -> Unit,
    onOpenSettings: () -> Unit = {},
    onReverse: () -> Unit = {},
) {
    val hosts by discovery.hosts.collectAsState()
    var showManual by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var diagnosisMessage by remember { mutableStateOf<String?>(null) }
    var forgetting by remember { mutableStateOf<DiscoveredHost?>(null) }
    var gamingFor by remember { mutableStateOf<DiscoveredHost?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DuvoMark()
            Text("Duvo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconTile(R.drawable.ic_sliders, "Configurações", onOpenSettings)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Seus PCs", style = MaterialTheme.typography.headlineLarge)
            Text(
                if (testing) "Testando a conexão com o PC…" else "Um toque e você está no controle.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (hosts.isEmpty()) {
                item {
                    Text("Procurando PCs na rede…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(hosts, key = { it.name.lowercase() + it.addresses.firstOrNull() }) { h ->
                val remoteId = h.uniqueId?.takeIf { h.paired }
                HostCard(
                    h,
                    onRemote = { if (remoteId != null) onOpenRemote(remoteId) else onOpenStream(h, StreamMode.REMOTE, true) },
                    onMirror = { onOpenStream(h, StreamMode.REMOTE, true) },
                    onMonitor = { onOpenStream(h, StreamMode.MONITOR, true) },
                    onGaming = { gamingFor = h },
                    onCloseMonitor = {
                        scope.launch {
                            val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching {
                                    val ep = remoteId?.let { discovery.endpointFor(it) }
                                    app.duvo.remote.host.HostClient(
                                        ep?.addresses ?: h.addresses, ep?.port ?: h.port,
                                        (context.applicationContext as app.duvo.remote.DuvoApp).certStore,
                                        deviceName = android.os.Build.MODEL ?: "Android", expectedUniqueId = remoteId,
                                    ).endHostSession()
                                }
                            }
                            android.widget.Toast.makeText(
                                context,
                                if (ok.isFailure) "Não foi possível falar com o PC." else if (ok.getOrNull() == true) "Segundo monitor desligado." else "Nada para desligar.",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    onForget = if (remoteId != null) ({ forgetting = h }) else null,
                )
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        .clickable(onClick = onScanQr)
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_qr), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    Text("Adicionar um PC com o QR code", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        .clickable(onClick = onReverse)
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_phone), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    Text("Mostrar este celular no PC", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            item {
                TextButton(onClick = { showManual = true }) {
                    Text("Adicionar IP manualmente", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showManual) ManualDialog(onDismiss = { showManual = false }) { ip, port ->
        discovery.addManual(ip, port)
        showManual = false
        // Bounded (<= 5 s): tell the user why a manual IP does not answer instead of leaving it silent.
        testing = true
        scope.launch {
            val d = try {
                ConnectionDiagnostics.run(context, listOf(ip), port)
            } finally {
                testing = false
            }
            if (d != Diagnosis.OK) diagnosisMessage = d.message()
        }
    }

    gamingFor?.let { h ->
        AlertDialog(
            onDismissRequest = { gamingFor = null },
            title = { Text("Como você quer jogar?") },
            text = { Text("Com a tela do PC, o jogo aparece no celular com os botões por cima. Só como controle, você olha o monitor ou a TV do PC e gasta menos bateria e rede.") },
            confirmButton = { TextButton(onClick = { gamingFor = null; onOpenStream(h, StreamMode.GAMING, true) }) { Text("Com a tela do PC") } },
            dismissButton = { TextButton(onClick = { gamingFor = null; onOpenStream(h, StreamMode.GAMING, false) }) { Text("Só como controle") } },
        )
    }

    forgetting?.let { h ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Esquecer PC") },
            text = { Text("Esquecer \"${h.name}\"? Para usar este PC de novo será preciso parear com o QR code.") },
            confirmButton = {
                TextButton(onClick = {
                    forgetting = null
                    h.uniqueId?.let(discovery::forget)
                }) { Text("Esquecer") }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancelar") } },
        )
    }

    diagnosisMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { diagnosisMessage = null },
            title = { Text("Sem conexão com o PC") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { diagnosisMessage = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun HostCard(
    h: DiscoveredHost,
    onRemote: () -> Unit,
    onMirror: () -> Unit,
    onMonitor: () -> Unit,
    onGaming: () -> Unit,
    onCloseMonitor: () -> Unit,
    onForget: (() -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_monitor), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(h.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    h.addresses.joinToString(", ") + ":" + h.port,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = PlexMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (h.paired) StatusDot("Pareado")
        }
        if (h.paired && h.uniqueId != null) {
            PrimaryButton("Controle remoto", onRemote, icon = R.drawable.ic_mouse)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeTile("Espelhar", R.drawable.ic_cast, onMirror, Modifier.weight(1f))
                ModeTile("Gaming", R.drawable.ic_gamepad, onGaming, Modifier.weight(1f))
                ModeTile("Monitor", R.drawable.ic_monitor_extend, onMonitor, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCloseMonitor) { Text("Desligar segundo monitor", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (onForget != null) {
                    TextButton(onClick = onForget) { Text("Esquecer este PC", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        } else {
            PrimaryButton("Transmitir a área de trabalho", onRemote, icon = R.drawable.ic_cast)
            Text(
                "Para usar como controle remoto, leia o QR code do PC.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")

@Composable
private fun ManualDialog(onDismiss: () -> Unit, onAdd: (String, Int) -> Unit) {
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(QrPayload.DEFAULT_PORT.toString()) }
    val p = port.toIntOrNull()
    val ok = IPV4.matches(ip.trim()) && p != null && p in 1..65535
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar IP manualmente") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ip, { ip = it }, label = { Text("Endereço IP") }, singleLine = true)
                OutlinedTextField(port, { port = it }, label = { Text("Porta") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onAdd(ip.trim(), p!!) }) { Text("Adicionar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
