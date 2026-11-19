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

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Test double of the Apollo/Sunshine GameStream server side (nvhttp.cpp): HTTP serverinfo + the
 * 4-phase pairing with the OTP extension, and HTTPS (client-cert) serverinfo/pairchallenge/applist.
 *
 * [plainCert] is what getservercert advertises; [tlsCert] is what the HTTPS port presents. They are
 * the same on a real host — tests make them differ to simulate a man in the middle.
 */
class FakeGameStreamHost(
    val plainCert: HeldCertificate = newHostCert(),
    val tlsCert: HeldCertificate = plainCert,
    var otpPin: String? = "1234",
    var otpPassphrase: String = "passphrase-0123456789",
    /** A spoofer on the LAN can claim PairStatus=1 over cleartext HTTP. */
    private val httpReportsPaired: Boolean = false,
    /** What cleartext HTTP serverinfo claims (a LAN spoofer can say anything); HTTPS always tells the truth. */
    private val httpAppVersion: String = APP_VERSION,
    private val httpCodecModeSupport: Int = CODEC_MODE_SUPPORT,
    val uniqueId: String = "FAKE-HOST-UNIQUE-ID",
) : AutoCloseable {
    /** Apollo's enable_input_only_mode: off, an appuuid=REMOTE_INPUT_UUID launch finds no such app (404). */
    var inputOnlyEnabled = true
    val http = MockWebServer()
    val https = MockWebServer()
    val pairedClients = mutableListOf<X509Certificate>()

    /** Fault injection: answer the next clientchallenge with HTTP 500 (session left half-done, like a lost reply). */
    var failNextClientChallenge = false

    /** Query of the last HTTPS /launch request. */
    var lastLaunch: okhttp3.HttpUrl? = null
        private set

    /** App id reported as running in serverinfo. */
    var currentGame = 0

    /** /cancel requests received. */
    var cancels = 0
        private set

    /** Short/garbage clientpairingsecret requests seen (the client's session-reset abort). */
    var abortsSeen = 0
        private set

    private val rnd = SecureRandom()
    private var key: ByteArray? = null
    private var serverSecret = ByteArray(0)
    private var serverChallenge = ByteArray(0)
    private var clientHash = ByteArray(0)
    private var clientCert: X509Certificate? = null

    init {
        val loopback = InetAddress.getByName("127.0.0.1")
        http.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = synchronized(this@FakeGameStreamHost) { onHttp(request) }
        }
        https.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = synchronized(this@FakeGameStreamHost) { onHttps(request) }
        }
        val km = HandshakeCertificates.Builder().heldCertificate(tlsCert).build().keyManager
        // Server side of a test fixture: accept any client cert at TLS level and decide per request,
        // exactly like Sunshine's verify-after-handshake.
        val acceptAnyClient = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                throw UnsupportedOperationException()
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(arrayOf(km), arrayOf(acceptAnyClient), null) }
        https.useHttps(ctx.socketFactory, false)
        https.requestClientAuth()
        http.start(loopback, 0)
        https.start(loopback, 0)
    }

    val httpPort get() = http.port

    override fun close() {
        http.shutdown()
        https.shutdown()
    }

    private fun xml(body: String, status: Int = 200, message: String? = null): MockResponse {
        val msg = message?.let { " status_message=\"$it\"" } ?: ""
        return MockResponse().setBody("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<root status_code=\"$status\"$msg>$body</root>")
    }

    private fun serverInfo(paired: Boolean, appVersion: String = APP_VERSION, codecs: Int = CODEC_MODE_SUPPORT) = xml(
        "<hostname>DuvoFake</hostname><appversion>$appVersion</appversion><GfeVersion>3.23.0.74</GfeVersion>" +
            "<uniqueid>$uniqueId</uniqueid><HttpsPort>${https.port}</HttpsPort><ExternalPort>${http.port}</ExternalPort>" +
            "<mac>00:00:00:00:00:00</mac><state>SUNSHINE_SERVER_FREE</state><currentgame>$currentGame</currentgame>" +
            "<ServerCodecModeSupport>$codecs</ServerCodecModeSupport><PairStatus>${if (paired) 1 else 0}</PairStatus>",
    )

    private fun onHttp(r: RecordedRequest): MockResponse {
        val url = r.requestUrl!!
        return when (url.encodedPath) {
            "/serverinfo" -> serverInfo(paired = httpReportsPaired, appVersion = httpAppVersion, codecs = httpCodecModeSupport)
            "/pair" -> pair(r)
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun verifiedClient(r: RecordedRequest): X509Certificate? {
        val peer = r.handshake?.peerCertificates?.firstOrNull() as? X509Certificate ?: return null
        return pairedClients.firstOrNull { it == peer }
    }

    private fun onHttps(r: RecordedRequest): MockResponse {
        val url = r.requestUrl!!
        if (verifiedClient(r) == null) return xml("", 401, "The client is not authorized. Certificate verification failed.")
        return when (url.encodedPath) {
            "/serverinfo" -> serverInfo(paired = url.queryParameter("uniqueid") != null)
            "/pair" -> if (url.queryParameter("phrase") == "pairchallenge") xml("<paired>1</paired>") else xml("", 404)
            "/applist" -> xml(
                "<App><IsHdrSupported>0</IsHdrSupported><AppTitle>Desktop</AppTitle><ID>881448767</ID></App>" +
                    "<App><IsHdrSupported>1</IsHdrSupported><AppTitle>Steam Big Picture</AppTitle><ID>1093255277</ID></App>",
            )
            "/cancel" -> { cancels++; currentGame = 0; xml("<cancel>1</cancel>") }
            "/launch" -> {
                lastLaunch = url
                if (!inputOnlyEnabled && url.queryParameter("appuuid") == "8CB5C136-DA67-4F99-B4A1-F9CD35005CF4") {
                    return xml("<gamesession>0</gamesession>", 404, "Cannot find requested application")
                }
                xml("<sessionUrl0>rtspenc://127.0.0.1:48010</sessionUrl0><gamesession>1</gamesession>")
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun pair(r: RecordedRequest): MockResponse {
        val q = r.requestUrl!!
        if (q.queryParameter("uniqueid") == null) return xml("", 400, "Missing uniqueid parameter")
        q.queryParameter("phrase")?.let { phrase ->
            if (phrase != "getservercert") return xml("", 404)
            val salt = q.queryParameter("salt")!!
            clientCert = parseCert(unhex(q.queryParameter("clientcert")!!))
            val otpauth = q.queryParameter("otpauth") ?: return xml("", 500, "fake host only supports OTP")
            val pending = otpPin ?: return xml("", 503, "OTP auth not available.")
            val expected = hex(sha256((pending + salt + otpPassphrase).toByteArray()))
            val pin = if (expected == otpauth) {
                otpPin = null // single use: consumed before the phase check, as in Apollo
                pending
            } else {
                hex(ByteArray(16).also(rnd::nextBytes)) // "Always return positive, attackers will fail in the next steps."
            }
            // Apollo: map_id_sess.emplace keeps an existing half-done session -> "Out of order", then fail_pair drops it.
            if (key != null) {
                key = null
                return xml("<paired>0</paired>", 400, "Out of order call to getservercert")
            }
            key = sha256(unhex(salt) + pin.toByteArray()).copyOf(16)
            return xml("<paired>1</paired><plaincert>${hex(plainCert.certificatePem().toByteArray())}</plaincert>")
        }
        val k = key ?: return xml("", 400, "Invalid uniqueid")
        q.queryParameter("clientchallenge")?.let {
            if (failNextClientChallenge) {
                failNextClientChallenge = false
                return MockResponse().setResponseCode(500)
            }
            val challenge = aes(Cipher.DECRYPT_MODE, k, unhex(it))
            serverSecret = ByteArray(16).also(rnd::nextBytes)
            serverChallenge = ByteArray(16).also(rnd::nextBytes)
            val hash = sha256(challenge + plainCert.certificate.signature + serverSecret)
            return xml("<paired>1</paired><challengeresponse>${hex(aes(Cipher.ENCRYPT_MODE, k, hash + serverChallenge))}</challengeresponse>")
        }
        q.queryParameter("serverchallengeresp")?.let {
            clientHash = aes(Cipher.DECRYPT_MODE, k, unhex(it))
            val sig = Signature.getInstance("SHA256withRSA").run {
                initSign(plainCert.keyPair.private); update(serverSecret); sign()
            }
            return xml("<paired>1</paired><pairingsecret>${hex(serverSecret + sig)}</pairingsecret>")
        }
        q.queryParameter("clientpairingsecret")?.let {
            val all = unhex(it)
            key = null
            if (all.size <= 16) {
                abortsSeen++
                return xml("<paired>0</paired>", 400, "Client pairing secret too short")
            }
            val secret = all.copyOfRange(0, 16)
            val sign = all.copyOfRange(16, all.size)
            val cert = clientCert!!
            val sameHash = sha256(serverChallenge + cert.signature + secret).contentEquals(clientHash)
            val verified = Signature.getInstance("SHA256withRSA").run {
                initVerify(cert.publicKey); update(secret); verify(sign)
            }
            return if (sameHash && verified) {
                pairedClients += cert
                xml("<paired>1</paired>")
            } else {
                xml("<paired>0</paired>")
            }
        }
        return xml("", 404, "Invalid pairing request")
    }

    companion object {
        const val APP_VERSION = "7.1.431.-1"
        const val CODEC_MODE_SUPPORT = 257
        fun newHostCert(): HeldCertificate = HeldCertificate.Builder().commonName("Sunshine Gamestream Host").rsa2048().build()

        fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
        fun fingerprint(c: HeldCertificate): String = sha256(c.certificate.encoded).joinToString("") { "%02x".format(it) }
        fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
        fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        private fun parseCert(pem: ByteArray) =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem)) as X509Certificate

        private fun aes(mode: Int, key: ByteArray, data: ByteArray): ByteArray {
            val padded = data.copyOf((data.size + 15) / 16 * 16)
            return Cipher.getInstance("AES/ECB/NoPadding").run { init(mode, SecretKeySpec(key, "AES")); doFinal(padded) }
        }
    }
}
