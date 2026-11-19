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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pairs against a real Duvo host. Skipped unless run with
 * `./gradlew :app:testDebugUnitTest --tests '*HostPairingLiveTest' -Pduvo.live=1 -Pduvo.pairUri='duvo://pair?...'`
 * using a fresh URI from the host's `POST /api/duvo/pair-qr`.
 */
class HostPairingLiveTest {
    @Test fun `pareia com o host real via QR e serverInfo fica pareado`() = runBlocking {
        assumeTrue("set -Pduvo.live=1 to run", System.getProperty("duvo.live") == "1")
        val uri = System.getProperty("duvo.pairUri").orEmpty()
        assumeTrue("set -Pduvo.pairUri", uri.isNotBlank())
        val p = QrPayload.parse(uri).getOrThrow()
        val certs = CertStore(InMemoryCertStorage())
        val client = HostClient(p.ips, p.port, certs, deviceName = "Duvo live test")

        // A wrong OTP must yield WrongPin and must not burn the real OTP: the host keeps it pending on a
        // mismatch, and our abort clears the half-done session keyed by our uniqueid.
        val wrongOtp = ((p.otp.toInt() + 1) % 10000).toString().padStart(4, '0')
        assertEquals(PairResult.WrongPin, client.pair(wrongOtp, p.passphrase, p.certSha256))

        val result = client.pair(p.otp, p.passphrase, p.certSha256)
        assertEquals(PairResult.Ok, result)

        val info = HostClient(p.ips, p.port, certs).serverInfo() // fresh client: pinned HTTPS + client cert
        println("live: host=${info.hostname} uniqueid=${info.uniqueId} version=${info.appVersion} paired=${info.paired}")
        assertEquals(p.certSha256, certs.pinnedHostCert(info.uniqueId))
        assertTrue(info.paired)
        println("live: apps=" + HostClient(p.ips, p.port, certs).appList().joinToString { it.title })
        // R13: QR-paired clients must be allowed to launch (never 403); the caller ends the session via /cancel.
        val launch = try {
            HostClient(p.ips, p.port, certs).launchDesktop(1280, 720, 30)
        } catch (e: HostStatusException) {
            assertTrue("launch must not be forbidden: ${e.message}", e.code != 403)
            null
        }
        println("live: launch=" + (launch?.let { it::class.simpleName } ?: "non-403 host error"))
    }
}
