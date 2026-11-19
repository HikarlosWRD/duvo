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

import android.app.Application
import android.net.Network
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.duvo.remote.DuvoApp
import app.duvo.remote.host.CONNECT_FAILED_PREFIX
import app.duvo.remote.host.HostClient
import app.duvo.remote.host.PairResult
import app.duvo.remote.net.ConnectionDiagnostics
import app.duvo.remote.net.Raced
import app.duvo.remote.net.WifiBinding
import app.duvo.remote.net.WifiBindingCore
import app.duvo.remote.net.message
import app.duvo.remote.net.raceWithDiagnosis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PairingUi {
    data object Missing : PairingUi
    data class Running(val hostName: String) : PairingUi
    data object Success : PairingUi
    data class Error(val message: String) : PairingUi
}

/** User-facing (pt-BR) text for a failed [PairResult]; null for [PairResult.Ok]. */
fun pairResultMessage(r: PairResult): String? = when (r) {
    PairResult.Ok -> null
    PairResult.WrongPin -> "Código expirado ou inválido. Gere um novo QR no PC."
    PairResult.CertMismatch -> "O certificado do PC não confere com o QR. Por segurança, o pareamento foi cancelado."
    is PairResult.Failed -> r.reason
}

/** Runs one pairing attempt for the payload in PendingPairing; scoped to the nav entry, so it survives rotation. */
class PairingViewModel(app: Application) : AndroidViewModel(app) {
    private val duvo = app as DuvoApp
    private val _state = MutableStateFlow<PairingUi>(PairingUi.Missing)
    val state: StateFlow<PairingUi> = _state.asStateFlow()

    /** Process bound to Wi-Fi while pairing, so the HTTP calls use the network the diagnosis probes. */
    private var wifi: WifiBindingCore<Network>.Lease? = null

    init {
        val p = duvo.pendingPairing.payload
        if (p != null) {
            _state.value = PairingUi.Running(p.name)
            val lease = WifiBinding.acquire(duvo).also { wifi = it }
            viewModelScope.launch {
                val result = try {
                    // The probe runs alongside the attempt: a network problem is reported at once, never after a long timeout.
                    val raced = raceWithDiagnosis(
                        attempt = {
                            HostClient(
                                p.ips, p.port, duvo.certStore,
                                deviceName = Build.MODEL ?: "Android",
                                socketFactory = lease.network?.socketFactory,
                            ).pair(p.otp, p.passphrase, p.certSha256)
                        },
                        diagnose = { ConnectionDiagnostics.run(duvo, p.ips, p.port) },
                        failed = { it is PairResult.Failed && it.reason.startsWith(CONNECT_FAILED_PREFIX) },
                    )
                    when (raced) {
                        is Raced.Diagnosed -> PairResult.Failed(raced.diagnosis.message())
                        is Raced.Completed -> raced.value
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // pair() maps failures itself; this guards CertStore(ctx) lazy init and anything unforeseen.
                    PairResult.Failed("Erro interno no pareamento: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    duvo.pendingPairing.clear() // the OTP is single-use; drop the secrets either way
                    lease.release()
                }
                _state.value = when (result) {
                    PairResult.Ok -> {
                        duvo.discovery.markPaired(p, pinnedUniqueId(p))
                        PairingUi.Success
                    }
                    else -> PairingUi.Error(pairResultMessage(result).orEmpty())
                }
            }
        }
    }

    /** Host uniqueid via pinned HTTPS serverinfo; null (not persisted) if that call fails. */
    private suspend fun pinnedUniqueId(p: app.duvo.remote.pairing.PairPayload): String? = try {
        val lease = WifiBinding.acquire(duvo)
        try {
            HostClient(p.ips, p.port, duvo.certStore, socketFactory = lease.network?.socketFactory)
                .serverInfo().takeIf { it.paired }?.uniqueId
        } finally {
            lease.release()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("PairingFlow", "serverinfo after pairing failed; host not persisted: ${e.message}")
        null
    }

    override fun onCleared() {
        duvo.pendingPairing.clear()
        wifi?.release()
    }
}

@Composable
fun PairingFlow(onDone: () -> Unit, vm: PairingViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state) { if (state == PairingUi.Success) onDone() }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val s = state) {
            is PairingUi.Running -> {
                CircularProgressIndicator()
                Text("Pareando com ${s.hostName}…", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            PairingUi.Success -> Text("Pareado!", style = MaterialTheme.typography.titleMedium)
            is PairingUi.Error -> {
                Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Button(onClick = onDone) { Text("Voltar") }
            }
            PairingUi.Missing -> {
                Text("Nenhum pareamento em andamento. Leia o QR code do PC novamente.", textAlign = TextAlign.Center)
                Button(onClick = onDone) { Text("Voltar") }
            }
        }
    }
}
