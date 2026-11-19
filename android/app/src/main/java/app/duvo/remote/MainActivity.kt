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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import app.duvo.remote.discovery.autoConnectTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.duvo.remote.discovery.HostDiscovery
import app.duvo.remote.pairing.PairPayload
import app.duvo.remote.pairing.QrPayload
import app.duvo.remote.pairing.deepLinkConfirmText
import app.duvo.remote.ui.HostListScreen
import app.duvo.remote.ui.PairingFlow
import app.duvo.remote.ui.ProbeScreen
import app.duvo.remote.ui.ScanQrScreen
import app.duvo.remote.ui.StreamScreen
import app.duvo.remote.ui.StreamViewModel
import app.duvo.remote.ui.remote.RemoteScreen
import app.duvo.remote.ui.remote.VolumeKeyHost
import app.duvo.remote.ui.GamepadEventHost
import app.duvo.remote.ui.stream.PadEditorScreen
import app.duvo.remote.ui.stream.StreamSettingsScreen
import app.duvo.remote.stream.StreamMode
import android.view.InputEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity(), VolumeKeyHost, GamepadEventHost {
    private val duvo: DuvoApp get() = application as DuvoApp
    private val discovery: HostDiscovery get() = duvo.discovery

    /** A pairing URI that arrived by deep link (R12a), waiting for the user to confirm it. */
    private val deepLinkPairing = MutableStateFlow<PairPayload?>(null)

    /** Set by the remote-control screen while resumed (physical volume keys -> PC volume). */
    override var volumeKeyHandler: ((up: Boolean) -> Boolean)? = null

    /** Set by the stream screen: physical controller events go to the PC instead of the UI. */
    override var gamepadHandler: ((InputEvent) -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gamepadHandler?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (gamepadHandler?.invoke(ev) == true) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    /** Volume key codes whose down was sent to the PC: their up is consumed too (no system volume panel). */
    private val consumedVolumeKeys = HashSet<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handlePairIntent(intent)
        val autoTarget = autoConnectTarget(
            freshLaunch = savedInstanceState == null,
            openedByPairLink = deepLinkPairing.value != null,
            lastUsed = runCatching { discovery.lastUsed() }.getOrNull(),
        )
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val appearance by duvo.appearance.collectAsState()
            app.duvo.remote.ui.theme.DuvoTheme(appearance) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    val link by deepLinkPairing.collectAsState()
                    // Any app or page can open duvo://pair: unlike the in-app scanner, ask before pairing.
                    link?.let { p ->
                        AlertDialog(
                            onDismissRequest = { deepLinkPairing.value = null },
                            title = { Text("Parear celular") },
                            text = { Text(deepLinkConfirmText(p)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    deepLinkPairing.value = null
                                    startPairing(p)
                                    nav.navigate("pair") { launchSingleTop = true }
                                }) { Text("Parear") }
                            },
                            dismissButton = { TextButton(onClick = { deepLinkPairing.value = null }) { Text("Cancelar") } },
                        )
                    }
                    LaunchedEffect(Unit) {
                        autoTarget?.let { id ->
                            discovery.touch(id)
                            nav.navigate("remote/${Uri.encode(id)}")
                        }
                    }
                    NavHost(nav, startDestination = "hosts") {
                        composable("hosts") {
                            Insets {
                                HostListScreen(
                                    discovery,
                                    onScanQr = { nav.navigate("scan") },
                                    onOpenRemote = { uniqueId ->
                                        discovery.touch(uniqueId)
                                        nav.navigate("remote/${Uri.encode(uniqueId)}")
                                    },
                                    onOpenStream = { h, mode, video ->
                                        h.uniqueId?.takeIf { h.paired }?.let { discovery.touch(it) }
                                        nav.navigate(streamRoute(h.addresses.joinToString(","), h.port, h.name, h.uniqueId?.takeIf { h.paired }, mode, video))
                                    },
                                    onOpenSettings = { nav.navigate("settings") },
                                    onReverse = { nav.navigate("reverse") },
                                )
                            }
                        }
                        composable("scan") {
                            Insets {
                                ScanQrScreen(discovery, onScanned = { payload ->
                                    // In memory only (R10): the OTP/passphrase never go into a route or the host list.
                                    duvo.pendingPairing.put(payload)
                                    nav.navigate("pair") { popUpTo("scan") { inclusive = true } }
                                })
                            }
                        }
                        composable("pair") {
                            Insets { PairingFlow(onDone = { nav.popBackStack("hosts", inclusive = false) }) }
                        }
                        composable("probe") { Insets { ProbeScreen() } }
                        composable("reverse") { Insets { app.duvo.remote.ui.ReverseHelpScreen(onBack = { nav.popBackStack() }) } }
                        composable("settings") {
                            Insets {
                                app.duvo.remote.ui.AppSettingsScreen(
                                    appearance = duvo.appearance,
                                    onAppearance = duvo::setAppearance,
                                    onBack = { nav.popBackStack() },
                                    onStreaming = { nav.navigate("streamSettings") },
                                    onPadEditor = { nav.navigate("padEditor") },
                                    onProbe = { nav.navigate("probe") },
                                )
                            }
                        }
                        composable("streamSettings") { Insets { StreamSettingsScreen(onBack = { nav.popBackStack() }) } }
                        composable("padEditor") { PadEditorScreen(onBack = { nav.popBackStack("hosts", inclusive = false) }) }
                        composable(
                            "remote/{hostId}",
                            arguments = listOf(navArgument("hostId") { type = NavType.StringType }),
                        ) { entry ->
                            val hostId = entry.arguments?.getString("hostId").orEmpty()
                            Insets {
                                RemoteScreen(
                                    hostId = hostId,
                                    onMirror = {
                                        nav.navigate(streamRoute("", QrPayload.DEFAULT_PORT, "", hostId, StreamMode.REMOTE, true)) {
                                            popUpTo("hosts") { inclusive = false }
                                        }
                                    },
                                    onGaming = { video ->
                                        nav.navigate(streamRoute("", QrPayload.DEFAULT_PORT, "", hostId, StreamMode.GAMING, video)) {
                                            popUpTo("hosts") { inclusive = false }
                                        }
                                    },
                                    onExit = { nav.popBackStack("hosts", inclusive = false) },
                                    onPairAgain = { nav.navigate("scan") { popUpTo("hosts") { inclusive = false } } },
                                )
                            }
                        }
                        composable(
                            "stream?${StreamViewModel.ARG_ADDRS}={${StreamViewModel.ARG_ADDRS}}" +
                                "&${StreamViewModel.ARG_PORT}={${StreamViewModel.ARG_PORT}}" +
                                "&${StreamViewModel.ARG_NAME}={${StreamViewModel.ARG_NAME}}" +
                                "&${StreamViewModel.ARG_HOST}={${StreamViewModel.ARG_HOST}}" +
                                "&${StreamViewModel.ARG_MODE}={${StreamViewModel.ARG_MODE}}" +
                                "&${StreamViewModel.ARG_VIDEO}={${StreamViewModel.ARG_VIDEO}}",
                            arguments = listOf(
                                navArgument(StreamViewModel.ARG_ADDRS) { type = NavType.StringType; defaultValue = "" },
                                navArgument(StreamViewModel.ARG_PORT) { type = NavType.IntType; defaultValue = QrPayload.DEFAULT_PORT },
                                navArgument(StreamViewModel.ARG_NAME) { type = NavType.StringType; defaultValue = "" },
                                navArgument(StreamViewModel.ARG_HOST) { type = NavType.StringType; defaultValue = "" },
                                navArgument(StreamViewModel.ARG_MODE) { type = NavType.StringType; defaultValue = StreamMode.GAMING.name },
                                navArgument(StreamViewModel.ARG_VIDEO) { type = NavType.BoolType; defaultValue = true },
                            ),
                        ) { entry ->
                            val hostArg = entry.arguments?.getString(StreamViewModel.ARG_HOST).orEmpty()
                            val modeArg = entry.arguments?.getString(StreamViewModel.ARG_MODE)
                            // Full screen: no inset padding, the stream hides the system bars.
                            StreamScreen(
                                onExit = {
                                    // Mirroring started from the remote control goes back to it (a fresh input session).
                                    if (modeArg == StreamMode.REMOTE.name && hostArg.isNotEmpty()) {
                                        nav.navigate("remote/${Uri.encode(hostArg)}") { popUpTo("hosts") { inclusive = false } }
                                    } else {
                                        nav.popBackStack("hosts", inclusive = false)
                                    }
                                },
                                onRestart = {
                                    val a = entry.arguments
                                    nav.navigate(
                                        streamRoute(
                                            a?.getString(StreamViewModel.ARG_ADDRS).orEmpty(),
                                            a?.getInt(StreamViewModel.ARG_PORT) ?: QrPayload.DEFAULT_PORT,
                                            a?.getString(StreamViewModel.ARG_NAME).orEmpty(),
                                            hostArg.ifEmpty { null },
                                            StreamMode.valueOf(modeArg ?: StreamMode.GAMING.name),
                                            a?.getBoolean(StreamViewModel.ARG_VIDEO) ?: true,
                                        ),
                                    ) { popUpTo(entry.destination.id) { inclusive = true } }
                                },
                                onPairAgain = {
                                    nav.navigate("scan") { popUpTo("hosts") { inclusive = false } }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairIntent(intent)
    }

    private fun handlePairIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || !data.scheme.equals("duvo", true) || !data.host.equals("pair", true)) return
        intent.data = null // consumed: never re-pair from a stale intent
        QrPayload.parse(data.toString()).fold(
            onSuccess = { deepLinkPairing.value = it },
            onFailure = { Toast.makeText(this, "Link de pareamento inválido. Use o QR do Duvo exibido no PC.", Toast.LENGTH_LONG).show() },
        )
    }

    /** Same path as a scanned QR: name/IPs/port to the host list, secrets only in PendingPairing. */
    private fun startPairing(p: PairPayload) {
        discovery.addFromQr(p)
        duvo.pendingPairing.put(p)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (volumeKeyHandler?.invoke(keyCode == KeyEvent.KEYCODE_VOLUME_UP) == true) {
                consumedVolumeKeys += keyCode
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (consumedVolumeKeys.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
    }

    override fun onStop() {
        discovery.stop()
        super.onStop()
    }
}

@Composable
private fun Insets(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() }
}

/** Route of the stream screen; [hostId] (paired PC) makes it resolve fresh addresses from discovery. */
internal fun streamRoute(addrs: String, port: Int, name: String, hostId: String?, mode: StreamMode, video: Boolean): String =
    "stream?${StreamViewModel.ARG_ADDRS}=${Uri.encode(addrs)}" +
        "&${StreamViewModel.ARG_PORT}=$port" +
        "&${StreamViewModel.ARG_NAME}=${Uri.encode(name)}" +
        "&${StreamViewModel.ARG_HOST}=${Uri.encode(hostId.orEmpty())}" +
        "&${StreamViewModel.ARG_MODE}=${mode.name}" +
        "&${StreamViewModel.ARG_VIDEO}=$video"
