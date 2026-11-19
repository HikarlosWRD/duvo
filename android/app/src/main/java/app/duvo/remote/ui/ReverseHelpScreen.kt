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

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.provider.Settings
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.duvo.remote.R
import app.duvo.remote.ui.theme.IconTile
import app.duvo.remote.ui.theme.PlexMono
import app.duvo.remote.ui.theme.PrimaryButton
import app.duvo.remote.ui.theme.SectionLabel
import app.duvo.remote.ui.theme.SettingsGroup
import app.duvo.remote.ui.theme.SettingsRow

/** Reverse mode: this phone's screen in a window on the PC (the PC side does the work through wireless debugging). */
@Composable
fun ReverseHelpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val ip = remember(context) { wifiAddress(context) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconTile(R.drawable.ic_back, "Voltar", onBack, outlined = false)
            Text("Mostrar este celular no PC", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            "A tela do celular aparece numa janela no PC e você controla com o mouse e o teclado. Isso é feito pelo PC, usando a depuração sem fio do Android.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ip != null) {
            SectionLabel("Endereço deste celular na rede")
            Text(ip, style = MaterialTheme.typography.titleMedium.copy(fontFamily = PlexMono))
        }
        SectionLabel("Passo a passo")
        SettingsGroup {
            SettingsRow("1. Ative a depuração sem fio", subtitle = "Opções do desenvolvedor > Depuração sem fio. Se não houver, toque 7 vezes em Número da versão.")
            SettingsRow("2. Abra o painel do Duvo no PC", subtitle = "Página Aparelhos, cartão \"Celular no PC\".")
            SettingsRow("3. Pareie com o código", subtitle = "Na depuração sem fio, toque em Parear dispositivo com código e digite no PC o endereço e o código mostrados.")
            SettingsRow("4. Conecte e abra", subtitle = "Digite o endereço principal da tela de depuração sem fio e use Abrir.")
        }
        PrimaryButton("Abrir opções do desenvolvedor", {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        })
    }
}

private fun wifiAddress(context: Context): String? = runCatching {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val net = cm.activeNetwork ?: return null
    val caps = cm.getNetworkCapabilities(net) ?: return null
    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
    cm.getLinkProperties(net)?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress
}.getOrNull()

/** Wi-Fi band label ("5 GHz" / "2,4 GHz") or null when not on Wi-Fi. */
fun wifiBandLabel(context: Context): String? = runCatching {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return null) ?: return null
    val info = caps.transportInfo as? WifiInfo ?: return null
    if (info.frequency in 2400..2500) "2,4 GHz" else if (info.frequency >= 4900) "5 GHz" else null
}.getOrNull()
