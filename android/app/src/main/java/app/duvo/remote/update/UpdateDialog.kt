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
package app.duvo.remote.update

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.duvo.remote.DuvoApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Nova versão": downloads, verifies and opens Android's installer. Nothing happens without the user's tap. */
@Composable
fun UpdateDialog(release: ReleaseInfo, app: DuvoApp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(-1) }
    var message by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) app.availableUpdate.value = null },
        title = { Text("Nova versão ${release.version}") },
        text = {
            Text(
                message ?: if (busy) {
                    if (progress >= 0) "Baixando... $progress%" else "Baixando..."
                } else {
                    "Há uma versão nova do Duvo. O arquivo é verificado (SHA-256) antes de instalar."
                },
            )
        },
        confirmButton = {
            TextButton(enabled = !busy && release.apkUrl != null, onClick = {
                busy = true
                message = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { UpdateInstaller.downloadVerified(ctx, release) { progress = it } }
                    busy = false
                    when (r) {
                        is DownloadResult.Failed -> message = r.message
                        is DownloadResult.Ok -> {
                            val note = UpdateInstaller.install(ctx, r.file)
                            if (note != null) message = note else app.availableUpdate.value = null
                        }
                    }
                }
            }) { Text("Atualizar") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { app.skipUpdate(release.version) }) { Text("Pular esta versão") }
        },
    )
}
