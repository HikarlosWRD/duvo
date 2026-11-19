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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/**
 * Reads `GET /duvo/status` and `/duvo/events` from a real Duvo host over mTLS (read-only, no input).
 * Skipped unless run with
 * `./gradlew :app:testDebugUnitTest --tests '*DuvoStatusLiveTest' -Pduvo.live=1 -Pduvo.pairUri='duvo://pair?...'`
 * using a fresh URI from the host's `POST /api/duvo/pair-qr`.
 */
class DuvoStatusLiveTest {
    @Test fun `status e long-poll do host real`() = runBlocking {
        assumeTrue("set -Pduvo.live=1 to run", System.getProperty("duvo.live") == "1")
        val uri = System.getProperty("duvo.pairUri").orEmpty()
        assumeTrue("set -Pduvo.pairUri", uri.isNotBlank())
        val p = QrPayload.parse(uri).getOrThrow()
        val certs = CertStore(InMemoryCertStorage())
        assertEquals(PairResult.Ok, HostClient(p.ips, p.port, certs, deviceName = "Duvo status live test").pair(p.otp, p.passphrase, p.certSha256))
        val info = HostClient(p.ips, p.port, certs).serverInfo()

        val tm = PinnedTrustManager(p.certSha256)
        val ctx = SSLContext.getInstance("TLS").apply { init(arrayOf(ClientKeyManager(certs.identity())), arrayOf(tm), SecureRandom()) }
        val http = OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, session -> tm.matches(session) }
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
        val base = "https://${p.ips.first()}:${info.httpsPort}"
        fun get(path: String): Pair<Int, String> =
            http.newCall(Request.Builder().url(base + path).get().build()).execute().use { it.code to it.body!!.string() }

        val (code, body) = get("/duvo/status")
        println("live: /duvo/status -> $code $body")
        assertEquals(200, code)
        val s = JSONObject(body)
        val seq = s.getLong("seq")

        // Stale `since` answers at once.
        var t0 = System.nanoTime()
        val (c1, b1) = get("/duvo/events?since=${seq + 1000}&timeout=25")
        val immediateMs = (System.nanoTime() - t0) / 1_000_000
        println("live: events(stale since) -> $c1 in ${immediateMs}ms $b1")
        assertEquals(200, c1)
        assertTrue(immediateMs < 2000)

        // Current `since` is held until a change or the timeout (3 s here).
        t0 = System.nanoTime()
        val (c2, b2) = get("/duvo/events?since=$seq&timeout=3")
        val heldMs = (System.nanoTime() - t0) / 1_000_000
        println("live: events(since=$seq, timeout=3) -> $c2 in ${heldMs}ms $b2")
        assertEquals(200, c2)
        val seq2 = JSONObject(b2).getLong("seq")
        assertTrue("held until timeout or answered on change", heldMs >= 2500 || seq2 != seq)

        val (c3, b3) = get("/duvo/status")
        println("live: /duvo/status again -> $c3 $b3")
    }
}
