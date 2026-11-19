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

package app.duvo.remote

import android.app.Application
import app.duvo.remote.update.ReleaseInfo
import app.duvo.remote.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import app.duvo.remote.discovery.AndroidNsdBrowser
import app.duvo.remote.discovery.DiscoveryHolder
import app.duvo.remote.discovery.FilePairedHostStore
import app.duvo.remote.discovery.HostDiscovery
import app.duvo.remote.host.CertStore
import app.duvo.remote.input.RemotePrefs
import app.duvo.remote.pairing.PendingPairing
import app.duvo.remote.stream.ConnectionModes

class DuvoApp : Application() {
    private val holder = DiscoveryHolder { HostDiscovery(AndroidNsdBrowser(this), FilePairedHostStore(File(filesDir, "paired_hosts.json")), unpin = { certStore.unpin(it) }) }
    val discovery: HostDiscovery get() = holder.instance
    val certStore: CertStore by lazy { CertStore(this) }
    val pendingPairing = PendingPairing()
    val remotePrefs: RemotePrefs by lazy { RemotePrefs(File(filesDir, "remote_prefs.json")) }
    val streamPrefs: app.duvo.remote.stream.StreamPrefs by lazy { app.duvo.remote.stream.StreamPrefs(File(filesDir, "stream_prefs.json")) }
    val layoutStore: app.duvo.remote.gamepad.LayoutStore by lazy { app.duvo.remote.gamepad.LayoutStore(File(filesDir, "pad_layouts")) }

    /** Stream and remote-control modes share MoonlightBridge's single connection: entering one stops the other. */
    val connectionModes = ConnectionModes()

    val uiPrefs: app.duvo.remote.ui.theme.UiPrefs by lazy { app.duvo.remote.ui.theme.UiPrefs(this) }

    /** Dark by default; changed in Configurações > Aparência and kept across launches. */
    val appearance: kotlinx.coroutines.flow.MutableStateFlow<app.duvo.remote.ui.theme.Appearance> by lazy {
        kotlinx.coroutines.flow.MutableStateFlow(app.duvo.remote.ui.theme.Appearance.load(this))
    }

    fun setAppearance(a: app.duvo.remote.ui.theme.Appearance) {
        app.duvo.remote.ui.theme.Appearance.save(this, a)
        appearance.value = a
    }

    /** A newer release found by [checkForUpdate]; shown as a dialog by MainActivity and in Configurações. */
    val availableUpdate = MutableStateFlow<ReleaseInfo?>(null)

    /** At most every 6 hours; silent when offline. */
    fun checkForUpdate(force: Boolean = false) {
        val sp = getSharedPreferences("duvo_update", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - sp.getLong("last_check", 0L) < 6 * 60 * 60 * 1000L) return
        CoroutineScope(Dispatchers.IO).launch {
            val r = UpdateChecker.fetchLatest() ?: return@launch
            sp.edit().putLong("last_check", now).apply()
            val current = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: return@launch
            availableUpdate.value = if (UpdateChecker.isNewer(current, r.version) && sp.getString("skipped", "") != r.version) r else null
        }
    }

    fun skipUpdate(version: String) {
        getSharedPreferences("duvo_update", MODE_PRIVATE).edit().putString("skipped", version).apply()
        availableUpdate.value = null
    }
}
