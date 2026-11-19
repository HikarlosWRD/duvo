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

package app.duvo.remote.host

import app.duvo.remote.pairing.QrPayload
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/**
 * Window/app/volume endpoints against a real Duvo host over mTLS. Never calls /duvo/power.
 *
 * Skipped unless run with `-Pduvo.live=1`. The first run also needs `-Pduvo.pairUri='duvo://pair?...'`
 * (fresh URI from `POST /api/duvo/pair-qr`); it pairs once and keeps the identity and host pin in
 * `DUVO_LIVE_IDENTITY_DIR` (default `app/build/duvo-live-identity`), so later runs reuse the same device.
 *
 * By default only lists windows and apps. Opt-in actions via environment variables:
 * - `DUVO_LIVE_WINDOW=<title substring>`: activate that window, show the desktop once, activate it again.
 * - `DUVO_LIVE_VOLUME=1`: set the volume to its current value (no audible change).
 * - `DUVO_LIVE_LAUNCH=<Start Menu name>`: launch that shortcut.
 *
 * SAFETY: right before EACH window action (activate, desktop) the test reads `/duvo/status` and aborts all
 * remaining actions unless `activeApp` is in `DUVO_LIVE_ALLOW` (comma-separated image names, default
 * `notepad.exe,charmap.exe`). Never widen the list to include a game or an app the user may be using.
 */
class DuvoWindowsLiveTest {
    /** File-backed identity so repeated live runs stay one paired device. */
    private class FileCertStorage(private val dir: File) : CertStore.Storage {
        private val idFile = File(dir, "identity.txt")
        private fun pinFile(hostId: String) = File(dir, "pin-" + hostId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".txt")
        override fun loadIdentity(): String? = idFile.takeIf { it.exists() }?.readText()
        override fun saveIdentity(blob: String) { dir.mkdirs(); idFile.writeText(blob) }
        override fun loadPin(hostId: String): String? = pinFile(hostId).takeIf { it.exists() }?.readText()
        override fun savePin(hostId: String, sha256: String) { dir.mkdirs(); pinFile(hostId).writeText(sha256) }
        override fun removePin(hostId: String) { pinFile(hostId).delete() }
    }

    private class Abort(msg: String) : Exception(msg)

    @Test fun `janelas, apps e volume do host real`() = runBlocking<Unit> {
        assumeTrue("set -Pduvo.live=1 to run", System.getProperty("duvo.live") == "1")
        val dir = File(System.getenv("DUVO_LIVE_IDENTITY_DIR")?.takeIf { it.isNotBlank() } ?: "build/duvo-live-identity")
        val hostFile = File(dir, "host.json")
        val certs = CertStore(FileCertStorage(dir))

        val uri = System.getProperty("duvo.pairUri").orEmpty()
        val host: JSONObject = if (hostFile.exists() && uri.isBlank()) {
            JSONObject(hostFile.readText()).also { println("live: reusing paired identity in $dir") }
        } else {
            assumeTrue("first run needs -Pduvo.pairUri", uri.isNotBlank())
            val p = QrPayload.parse(uri).getOrThrow()
            assertEquals(PairResult.Ok, HostClient(p.ips, p.port, certs, deviceName = "Duvo windows live test").pair(p.otp, p.passphrase, p.certSha256))
            val info = HostClient(p.ips, p.port, certs).serverInfo()
            JSONObject().put("ip", p.ips.first()).put("httpsPort", info.httpsPort).put("certSha256", p.certSha256).also {
                dir.mkdirs(); hostFile.writeText(it.toString())
            }
        }

        val tm = PinnedTrustManager(host.getString("certSha256"))
        val ctx = SSLContext.getInstance("TLS").apply { init(arrayOf(ClientKeyManager(certs.identity())), arrayOf(tm), SecureRandom()) }
        val http = OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, session -> tm.matches(session) }
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val base = "https://${host.getString("ip")}:${host.getInt("httpsPort")}"
        fun get(path: String): Pair<Int, String> =
            http.newCall(Request.Builder().url(base + path).get().build()).execute().use { it.code to it.body!!.string() }
        fun post(path: String): Pair<Int, String> =
            http.newCall(Request.Builder().url(base + path).post(ByteArray(0).toRequestBody("application/json".toMediaType())).build())
                .execute().use { it.code to it.body!!.string() }
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        val allow = (System.getenv("DUVO_LIVE_ALLOW")?.takeIf { it.isNotBlank() } ?: "notepad.exe,charmap.exe")
            .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        /** Abort unless the foreground app is on the allow-list. Call right before every window action. */
        fun requireSafeForeground(action: String) {
            val app = JSONObject(get("/duvo/status").second).optString("activeApp").lowercase()
            if (app !in allow) throw Abort("foreground '$app' not in $allow before $action")
            println("live: foreground '$app' ok before $action")
        }

        val (wc, wb) = get("/duvo/windows")
        val windows = JSONObject(wb).getJSONArray("windows")
        println("live: /duvo/windows -> $wc, ${windows.length()} windows")
        for (i in 0 until windows.length()) println("  window: ${windows.getJSONObject(i)}")
        assertEquals(200, wc)

        val (ac, ab) = get("/duvo/apps")
        val apps = JSONObject(ab).getJSONArray("apps")
        println("live: /duvo/apps -> $ac, ${apps.length()} apps; first: ${if (apps.length() > 0) apps.getJSONObject(0) else null}")
        assertEquals(200, ac)

        println("live: activate missing -> ${post("/duvo/windows/activate?id=1")}")

        try {
            System.getenv("DUVO_LIVE_WINDOW")?.takeIf { it.isNotBlank() }?.let { needle ->
                val w = (0 until windows.length()).map { windows.getJSONObject(it) }.firstOrNull { it.getString("title").contains(needle) }
                assertNotNull("window containing '$needle'", w)
                val id = w!!.getLong("id")
                requireSafeForeground("activate")
                println("live: activate $id (${w.getString("title")}) -> ${post("/duvo/windows/activate?id=$id")}")
                Thread.sleep(1500)
                requireSafeForeground("desktop")
                println("live: desktop -> ${post("/duvo/desktop")}")
                Thread.sleep(1500)
                requireSafeForeground("activate again")
                println("live: activate again $id -> ${post("/duvo/windows/activate?id=$id")}")
                Thread.sleep(1500)
                println("live: status after re-activate -> ${get("/duvo/status").second}")
            }
        } catch (e: Abort) {
            println("live: ABORTED window actions: ${e.message}")
        }

        if (System.getenv("DUVO_LIVE_VOLUME") == "1") {
            val vol = JSONObject(get("/duvo/status").second).getJSONObject("volume")
            if (!vol.isNull("level")) {
                val level = vol.getInt("level")
                println("live: volume level=$level (same value) -> ${post("/duvo/volume?level=$level")}")
            }
        }

        System.getenv("DUVO_LIVE_LAUNCH")?.takeIf { it.isNotBlank() }?.let { name ->
            val app = (0 until apps.length()).map { apps.getJSONObject(it) }.firstOrNull { it.getString("name") == name }
            assertNotNull("Start Menu entry '$name'", app)
            println("live: launch ${app!!} -> ${post("/duvo/apps/launch?id=${enc(app.getString("id"))}")}")
        }
    }
}
