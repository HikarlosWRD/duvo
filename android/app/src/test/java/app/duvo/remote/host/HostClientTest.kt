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

import app.duvo.remote.host.FakeGameStreamHost.Companion.fingerprint
import app.duvo.remote.host.FakeGameStreamHost.Companion.newHostCert
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HostClientTest {
    private val storage = InMemoryCertStorage()
    private val certs = CertStore(storage)
    private var host: FakeGameStreamHost? = null

    @After fun tearDown() { host?.close() }

    private fun start(h: FakeGameStreamHost) = h.also { host = it }
    private fun client(h: FakeGameStreamHost, ips: List<String> = listOf("127.0.0.1")) =
        HostClient(ips, h.httpPort, certs, deviceName = "Duvo test")

    @Test fun `serverInfo le nome uniqueid e versao`() = runBlocking {
        val h = start(FakeGameStreamHost())
        val info = client(h).serverInfo()
        assertEquals("DuvoFake", info.hostname)
        assertEquals(h.uniqueId, info.uniqueId)
        assertEquals("7.1.431.-1", info.appVersion)
        assertEquals(h.https.port, info.httpsPort)
        assertFalse(info.paired)
    }

    @Test fun `pair com cert do host diferente do QR retorna CertMismatch`() = runBlocking {
        val h = start(FakeGameStreamHost())
        val qrFingerprint = fingerprint(newHostCert()) // QR names a different certificate
        val r = client(h).pair("1234", h.otpPassphrase, qrFingerprint)
        assertEquals(PairResult.CertMismatch, r)
        assertNull(certs.pinnedHostCert(h.uniqueId))
        assertTrue(h.pairedClients.isEmpty())
    }

    @Test fun `pair com TLS apresentando cert diferente do QR retorna CertMismatch`() = runBlocking {
        // plaincert matches the QR, but the HTTPS port presents another cert (MITM on the TLS leg).
        val real = newHostCert()
        val h = start(FakeGameStreamHost(plainCert = real, tlsCert = newHostCert()))
        val r = client(h).pair("1234", h.otpPassphrase, fingerprint(real))
        assertEquals(PairResult.CertMismatch, r)
        assertNull(certs.pinnedHostCert(h.uniqueId))
    }

    @Test fun `conexao com host pareado cujo cert mudou e recusada`() = runBlocking {
        val h = start(FakeGameStreamHost())
        certs.pin(h.uniqueId, fingerprint(newHostCert())) // pinned earlier; the host now presents another cert
        try {
            client(h).serverInfo()
            fail("expected CertChangedException")
        } catch (e: CertChangedException) {
            assertEquals(h.uniqueId, e.hostId)
            assertEquals(fingerprint(h.tlsCert), e.actualSha256)
        }
    }

    @Test fun `OTP errado retorna WrongPin`() = runBlocking {
        val h = start(FakeGameStreamHost())
        val r = client(h).pair("9999", h.otpPassphrase, fingerprint(h.plainCert))
        assertEquals(PairResult.WrongPin, r)
        assertTrue(h.pairedClients.isEmpty())
        assertNull(certs.pinnedHostCert(h.uniqueId))
    }

    @Test fun `OTP expirado retorna WrongPin`() = runBlocking {
        val h = start(FakeGameStreamHost(otpPin = null)) // host answers 503 "OTP auth not available."
        assertEquals(PairResult.WrongPin, client(h).pair("1234", h.otpPassphrase, fingerprint(h.plainCert)))
    }

    @Test fun `pair com OTP correto pareia fixa o cert e serverInfo fica pareado`() = runBlocking {
        val h = start(FakeGameStreamHost())
        val c = client(h)
        assertEquals(PairResult.Ok, c.pair("1234", h.otpPassphrase, fingerprint(h.plainCert).uppercase()))
        assertEquals(fingerprint(h.plainCert), certs.pinnedHostCert(h.uniqueId))
        assertEquals(listOf(certs.clientCert()), h.pairedClients)
        assertTrue(client(h).serverInfo().paired)
        assertEquals(listOf(HostApp(881448767, "Desktop", false), HostApp(1093255277, "Steam Big Picture", true)), c.appList())
    }

    @Test fun `tenta os IPs do payload em ordem`() = runBlocking {
        val h = start(FakeGameStreamHost())
        // 127.0.0.2 has nothing listening on this port (the fake binds 127.0.0.1 only).
        val info = client(h, listOf("127.0.0.2", "127.0.0.1")).serverInfo()
        assertEquals(h.uniqueId, info.uniqueId)
    }

    @Test fun `PairStatus 1 por HTTP em host nao fixado nao conta como pareado`() = runBlocking {
        val h = start(FakeGameStreamHost(httpReportsPaired = true))
        assertFalse(client(h).serverInfo().paired)
    }

    @Test fun `PairStatus 1 por HTTP com HTTPS 401 nao conta como pareado`() = runBlocking {
        val h = start(FakeGameStreamHost(httpReportsPaired = true))
        certs.pin(h.uniqueId, fingerprint(h.tlsCert)) // pinned, but the host does not know our client cert -> 401
        assertFalse(client(h).serverInfo().paired)
    }

    @Test fun `falha do keystore vira Failed em vez de excecao`() = runBlocking {
        val h = start(FakeGameStreamHost())
        val broken = object : CertStore.Storage by InMemoryCertStorage() {
            override fun loadIdentity(): String? = throw IllegalStateException("keystore indisponivel")
        }
        val r = HostClient(listOf("127.0.0.1"), h.httpPort, CertStore(broken)).pair("1234", h.otpPassphrase, fingerprint(h.plainCert))
        assertTrue(r.toString(), r is PairResult.Failed)
    }

    @Test fun `erro no meio do handshake envia abort e o proximo QR pareia`() = runBlocking {
        val h = start(FakeGameStreamHost())
        h.failNextClientChallenge = true
        val first = client(h).pair("1234", h.otpPassphrase, fingerprint(h.plainCert))
        assertTrue(first.toString(), first is PairResult.Failed)
        assertEquals(1, h.abortsSeen)
        // The first attempt consumed the OTP at getservercert (Apollo's verify_and_take), so the PC shows a
        // new QR; without the abort its OTP would be burned by "Out of order call to getservercert".
        h.otpPin = "5678"
        assertEquals(PairResult.Ok, client(h).pair("5678", h.otpPassphrase, fingerprint(h.plainCert)))
    }

    @Test fun `otpauth e o sha256 maiusculo de pin sal e senha como no host`() {
        // printf '%s' "1234" "00112233445566778899AABBCCDDEEFF" "abcdefghijklmnopqrstuvwx" | sha256sum
        assertEquals(
            "34D6EAB251A3062DE09CF4FF41094D474088901578BC9FD9CC2BECEF96C30D10",
            PairingCrypto.otpAuth("1234", "00112233445566778899AABBCCDDEEFF", "abcdefghijklmnopqrstuvwx"),
        )
    }

    @Test fun `CertStore gera o cert do cliente uma vez e o reaproveita`() {
        val first = certs.clientCert()
        assertEquals("RSA", first.publicKey.algorithm)
        assertEquals(first, CertStore(storage).clientCert()) // reloaded from storage, not regenerated
        assertEquals(certs.clientUniqueId(), CertStore(storage).clientUniqueId())
    }

    @Test fun `launchDesktop pede corever 1 e devolve endereco versao e codecs do host`() = runBlocking {
        val h = start(FakeGameStreamHost())
        val c = client(h, listOf("127.0.0.2", "127.0.0.1"))
        assertEquals(PairResult.Ok, c.pair("1234", h.otpPassphrase, fingerprint(h.plainCert)))

        val r = c.launchDesktop(1920, 1080, 60) as LaunchResult.Ok

        assertEquals(881448767, r.appId)
        assertEquals("rtspenc://127.0.0.1:48010", r.sessionUrl)
        assertEquals("127.0.0.1", r.addr)
        assertEquals("7.1.431.-1", r.appVersion)
        assertEquals(257, r.codecModeSupport)
        val q = h.lastLaunch!!
        assertEquals("1", q.queryParameter("corever"))
        assertEquals("1920x1080x60", q.queryParameter("mode"))
        assertEquals(PairingCrypto.hex(r.riKey), q.queryParameter("rikey"))
        assertEquals(r.riKeyId.toString(), q.queryParameter("rikeyid"))
    }

    @Test fun `launchDesktop manda tela virtual audio no PC e controles e encerra o Desktop ja rodando`() = runBlocking {
        val h = start(FakeGameStreamHost())
        val c = client(h)
        assertEquals(PairResult.Ok, c.pair("1234", h.otpPassphrase, fingerprint(h.plainCert)))
        h.currentGame = 881448767

        c.launchDesktop(2000, 1200, 60, LaunchOptions(virtualDisplay = true, hostAudio = true, controllers = 1))

        assertEquals(1, h.cancels)
        val q = h.lastLaunch!!
        assertEquals("1", q.queryParameter("virtualDisplay"))
        assertEquals("1", q.queryParameter("localAudioPlayMode"))
        assertEquals("1", q.queryParameter("remoteControllersBitmap"))
        assertEquals("1", q.queryParameter("gcmap"))
    }

    @Test fun `launchDesktop sempre encerra a sessao anterior para aplicar as opcoes`() = runBlocking {
        val h = start(FakeGameStreamHost())
        val c = client(h)
        assertEquals(PairResult.Ok, c.pair("1234", h.otpPassphrase, fingerprint(h.plainCert)))
        c.launchDesktop(1920, 1080, 60)
        assertEquals(1, h.cancels)
        assertEquals("0", h.lastLaunch!!.queryParameter("virtualDisplay"))
        assertEquals("0", h.lastLaunch!!.queryParameter("localAudioPlayMode"))
    }

    @Test fun `launchInputOnly manda appuuid`() = runBlocking {
        val h = start(FakeGameStreamHost())
        val c = client(h)
        assertEquals(PairResult.Ok, c.pair("1234", h.otpPassphrase, fingerprint(h.plainCert)))

        val r = c.launchInputOnly(2400, 1080) as LaunchResult.Ok

        val q = h.lastLaunch!!
        assertEquals("8CB5C136-DA67-4F99-B4A1-F9CD35005CF4", q.queryParameter("appuuid"))
        assertTrue(q.toString().contains("appuuid=8CB5C136-DA67-4F99-B4A1-F9CD35005CF4"))
        assertNull(q.queryParameter("appid")) // the Desktop app is not launched: no applist lookup
        assertEquals("2400x1080x30", q.queryParameter("mode"))
        assertEquals("1", q.queryParameter("corever"))
        assertEquals("0", q.queryParameter("localAudioPlayMode"))
        assertEquals(PairingCrypto.hex(r.riKey), q.queryParameter("rikey"))
        assertEquals(r.riKeyId.toString(), q.queryParameter("rikeyid"))
        assertEquals("rtspenc://127.0.0.1:48010", r.sessionUrl)
        assertEquals("127.0.0.1", r.addr)
        assertEquals("7.1.431.-1", r.appVersion)
        assertEquals(257, r.codecModeSupport)
    }

    @Test fun `launchInputOnly usa versao e codecs do serverinfo HTTPS fixado`() = runBlocking {
        val h = start(FakeGameStreamHost(httpAppVersion = "7.1.0.0", httpCodecModeSupport = 1))
        val c = client(h)
        assertEquals(PairResult.Ok, c.pair("1234", h.otpPassphrase, fingerprint(h.plainCert)))

        val r = c.launchInputOnly(1920, 1080, 30) as LaunchResult.Ok

        assertEquals("7.1.431.-1", r.appVersion)
        assertEquals(257, r.codecModeSupport)
    }

    @Test fun `launchInputOnly sem pareamento falha com NotPaired`() {
        val h = start(FakeGameStreamHost())
        val c = client(h)
        try {
            runBlocking { c.launchInputOnly(1920, 1080) }
            fail("esperava NotPairedException")
        } catch (e: NotPairedException) {
            // expected
        }
    }

    @Test fun `CertStore unpin esquece o cert fixado do host`() {
        certs.pin("HOST-1", "AB".repeat(32))
        certs.unpin("HOST-1")
        assertNull(certs.pinnedHostCert("HOST-1"))
    }

    @Test fun `launchDesktop usa versao e codecs do serverinfo HTTPS fixado e nao do HTTP em claro`() = runBlocking {
        // A LAN spoofer answering cleartext serverinfo with an old appversion would downgrade the
        // control/input encryption moonlight-common-c picks (APP_VERSION_AT_LEAST(7, 1, 431)).
        val h = start(FakeGameStreamHost(httpAppVersion = "7.1.0.0", httpCodecModeSupport = 1))
        val c = client(h)
        assertEquals(PairResult.Ok, c.pair("1234", h.otpPassphrase, fingerprint(h.plainCert)))

        val r = c.launchDesktop(1920, 1080, 60) as LaunchResult.Ok

        assertEquals("7.1.431.-1", r.appVersion)
        assertEquals(257, r.codecModeSupport)
    }

    @Test fun `launchInputOnly com modo so input desligado no PC lanca InputOnlyDisabled`() {
        val h = start(FakeGameStreamHost())
        val c = client(h)
        assertEquals(PairResult.Ok, runBlocking { c.pair("1234", h.otpPassphrase, fingerprint(h.plainCert)) })
        h.inputOnlyEnabled = false
        try {
            runBlocking { c.launchInputOnly(1920, 1080) }
            fail("esperava InputOnlyDisabledException")
        } catch (e: InputOnlyDisabledException) {
            // expected
        }
    }

    @Test fun `com expectedUniqueId um endereco de outro PC e ignorado`() = runBlocking {
        val other = FakeGameStreamHost(uniqueId = "OUTRO-PC")
        try {
            val h = start(FakeGameStreamHost())
            assertEquals(PairResult.Ok, client(h).pair("1234", h.otpPassphrase, fingerprint(h.plainCert)))
            // Both answer on 127.0.0.1 but on different ports: model "another PC" with a client whose
            // only address reaches it, then check the right one is still found among several.
            val wrong = HostClient(listOf("127.0.0.1"), other.httpPort, certs, expectedUniqueId = h.uniqueId)
            try {
                wrong.serverInfo()
                fail("esperava IOException: o único endereço é de outro PC")
            } catch (e: java.io.IOException) {
                assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("outro PC"))
            }
            val right = HostClient(listOf("127.0.0.1"), h.httpPort, certs, expectedUniqueId = h.uniqueId)
            assertEquals(h.uniqueId, right.serverInfo().uniqueId)
        } finally {
            other.close()
        }
    }
}
