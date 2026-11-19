/*
 * Duvo - remote control and streaming for a Windows PC.
 * Copyright (C) 2026 Duvo contributors
 *
 * Portions ported from Moonlight Android's NvHTTP.java and PairingManager.java
 * (https://github.com/moonlight-stream/moonlight-android, GPLv3, Copyright (C) Moonlight contributors)
 * and the OTP pairing extension from Artemis (https://github.com/ClassicOldSong/moonlight-android, GPLv3).
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

import app.duvo.remote.host.PairingCrypto.hex
import app.duvo.remote.host.PairingCrypto.unhex
import app.duvo.remote.input.InputHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.IOException
import java.net.Proxy
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.Principal
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import javax.xml.parsers.DocumentBuilderFactory

data class ServerInfo(
    val hostname: String,
    val uniqueId: String,
    val appVersion: String,
    val httpsPort: Int,
    /** True only when read over HTTPS with our pinned cert and the host accepted our client cert. */
    val paired: Boolean,
    val state: String?,
    /** SCM_* bit mask of the codecs the host can encode (moonlight-common-c needs it). */
    val codecModeSupport: Int = 0,
    /** App id running on the host (0 = none). */
    val currentGame: Int = 0,
)

/** Per-launch choices (spec §8.3.2): what the host captures, where audio plays, gamepads present. */
data class LaunchOptions(
    val virtualDisplay: Boolean = false,
    val hostAudio: Boolean = false,
    /** Bit n set = gamepad n is connected at launch (Moonlight's remoteControllersBitmap). */
    val controllers: Int = 0,
    /** Stop a Desktop session already running first, so these options (screen) take effect. */
    val restartRunning: Boolean = true,
)

data class HostApp(val id: Int, val title: String, val hdrSupported: Boolean)

/** Prefix of [PairResult.Failed] reasons caused by a connection (not protocol) error; the UI runs a diagnosis on these. */
const val CONNECT_FAILED_PREFIX = "Não foi possível falar com o PC"

sealed interface PairResult {
    data object Ok : PairResult
    data object WrongPin : PairResult
    data object CertMismatch : PairResult
    data class Failed(val reason: String) : PairResult
}

sealed interface LaunchResult {
    /**
     * [riKey]/[riKeyId] are the stream's AES key/IV id we generated and sent; the stream layer needs them,
     * along with the address that answered ([addr]), the host [appVersion] and its [codecModeSupport].
     */
    class Ok(
        val appId: Int,
        val sessionUrl: String?,
        val riKey: ByteArray,
        val riKeyId: Int,
        val addr: String = "",
        val appVersion: String = "",
        val codecModeSupport: Int = 0,
    ) : LaunchResult
    data class Failed(val reason: String) : LaunchResult
}

/** The host answered with a non-200 GameStream status (XML `status_code`) or HTTP error. */
class HostStatusException(val code: Int, message: String?) : IOException("O PC respondeu $code: ${message.orEmpty()}")

/** A host we pinned now presents another certificate. The UI should offer to pair again. */
class CertChangedException(val hostId: String, val expectedSha256: String, val actualSha256: String) :
    IOException("O certificado do PC mudou desde o pareamento")

class NotPairedException(hostId: String) : IOException("Este aparelho não está pareado com o PC $hostId")

/** The host refused an input-only launch because Apollo's `enable_input_only_mode` is off (retrying cannot help). */
class InputOnlyDisabledException : IOException("O modo só input está desativado no PC")

/**
 * GameStream HTTP(S) client for an Duvo (Apollo) host: serverinfo over HTTP, mutual-TLS HTTPS with the
 * host cert pinned by SHA-256, and OTP pairing. [addrs] are tried in order, [CONNECT_TIMEOUT_MS] each.
 */
class HostClient(
    private val addrs: List<String>,
    private val port: Int,
    private val certs: CertStore,
    private val deviceName: String = "Duvo",
    /** Sockets for the host (the Wi-Fi network's factory while pairing/streaming); null = default network. */
    socketFactory: SocketFactory? = null,
    /**
     * When set, an address whose serverinfo reports another uniqueid is skipped: [addrs] may then mix
     * candidates (e.g. every mDNS host after the paired PC changed IP) without reaching another PC.
     */
    private val expectedUniqueId: String? = null,
) : InputHost {
    constructor(addr: String, port: Int, certs: CertStore) : this(listOf(addr), port, certs)

    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // Like NvHTTP: no pooled connections (hosts drop idle sockets; a stale one costs a timeout).
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .proxy(Proxy.NO_PROXY)
        .retryOnConnectionFailure(false)
        .apply { if (socketFactory != null) socketFactory(socketFactory) }
        .build()

    /** Address that answered last; tried first next time. */
    @Volatile private var lastAddr: String? = null

    suspend fun serverInfo(): ServerInfo = io {
        val (addr, plain) = resolve()
        val pin = certs.pinnedHostCert(plain.uniqueId) ?: return@io plain
        try {
            parseServerInfo(httpsGet(addr, plain, pin, "serverinfo"), overPinnedHttps = true)
        } catch (e: HostStatusException) {
            if (e.code == 401) plain else throw e // host no longer knows our client cert
        }
    }

    suspend fun appList(): List<HostApp> = io {
        val (addr, info, pin) = paired()
        appList(addr, info, pin)
    }

    /**
     * Ends whatever session the PC is running (its Desktop session, and with it the extra virtual monitor).
     * Returns false when nothing was running.
     */
    suspend fun endHostSession(): Boolean = io {
        val (addr, plain, pin) = paired()
        val info = parseServerInfo(httpsGet(addr, plain, pin, "serverinfo"), overPinnedHttps = true)
        // The Desktop session reports currentgame 0 even while it holds the virtual monitor, so always ask.
        httpsGet(addr, info, pin, "cancel")
        true
    }

    suspend fun launchDesktop(w: Int, h: Int, fps: Int, opts: LaunchOptions = LaunchOptions()): LaunchResult = io {
        val (addr, plain, pin) = paired()
        // appversion/codec support steer moonlight-common-c's protocol (incl. control/input encryption
        // from 7.1.431): take them from pinned HTTPS, never from spoofable cleartext serverinfo.
        val info = parseServerInfo(httpsGet(addr, plain, pin, "serverinfo"), overPinnedHttps = true)
        val app = appList(addr, info, pin).firstOrNull { it.title.equals("Desktop", ignoreCase = true) }
            ?: return@io LaunchResult.Failed("O PC não tem o app Desktop")
        if (opts.restartRunning) {
            // Joining keeps the old screen/audio choice; end it so this launch applies ours.
            runCatching { httpsGet(addr, info, pin, "cancel") }
        }
        launch(addr, info, pin, "appid" to app.id.toString(), app.id, w, h, fps, opts)
    }

    /**
     * Apollo's input-only session (`appuuid` = [REMOTE_INPUT_UUID], needs `enable_input_only_mode` on
     * the host): the control/input channels run while the host encodes a single dummy frame and
     * idles, so nothing has to be decoded. [w]x[h] is the device screen, at [fps] (30 by default).
     */
    override suspend fun launchInputOnly(w: Int, h: Int, fps: Int): LaunchResult = io {
        val (addr, plain, pin) = paired()
        val info = parseServerInfo(httpsGet(addr, plain, pin, "serverinfo"), overPinnedHttps = true)
        try {
            launch(addr, info, pin, "appuuid" to REMOTE_INPUT_UUID, 0, w, h, fps, LaunchOptions(restartRunning = false))
        } catch (e: HostStatusException) {
            // Apollo with enable_input_only_mode off looks REMOTE_INPUT_UUID up as a regular app: 404.
            if (e.code == 404) throw InputOnlyDisabledException() else throw e
        }
    }

    /** One /launch of [app] (`appid` or `appuuid` query pair) with a fresh remote-input key. */
    private fun launch(
        addr: String,
        info: ServerInfo,
        pin: String,
        app: Pair<String, String>,
        appId: Int,
        w: Int,
        h: Int,
        fps: Int,
        opts: LaunchOptions,
    ): LaunchResult {
        val riKey = PairingCrypto.random(16)
        val riKeyId = SecureRandom().nextInt()
        val doc = httpsGet(
            addr, info, pin, "launch",
            listOf(
                app,
                "mode" to "${w}x${h}x$fps",
                "additionalStates" to "1",
                "sops" to "0",
                "rikey" to hex(riKey),
                "rikeyid" to riKeyId.toString(),
                "localAudioPlayMode" to if (opts.hostAudio) "1" else "0",
                "surroundAudioInfo" to "196610", // stereo
                "remoteControllersBitmap" to opts.controllers.toString(),
                "gcmap" to opts.controllers.toString(),
                "virtualDisplay" to if (opts.virtualDisplay) "1" else "0",
                "gcpersist" to "0",
                // moonlight-common-c's LiGetLaunchUrlQueryParameters(): video/control encryption v2 + encrypted RTSP
                "corever" to "1",
            ),
            readTimeoutMs = LAUNCH_READ_TIMEOUT_MS,
        )
        return if (doc.text("gamesession") == "0" || doc.text("gamesession") == null) {
            LaunchResult.Failed("O PC não iniciou a sessão")
        } else {
            LaunchResult.Ok(appId, doc.text("sessionUrl0"), riKey, riKeyId, addr, info.appVersion, info.codecModeSupport)
        }
    }

    /**
     * OTP pairing (port of Moonlight's PairingManager.pair with Artemis' otpauth). The host cert
     * received in getservercert must match [expectedCertSha256] (from the QR); it is then pinned
     * for TLS and stored per host uniqueid on success.
     */
    suspend fun pair(otp: String, passphrase: String, expectedCertSha256: String): PairResult = io {
        val expected = expectedCertSha256.lowercase()
        try {
            val (addr, info) = resolve()
            doPair(addr, info, otp, passphrase, expected)
        } catch (e: CertChangedException) {
            PairResult.CertMismatch
        } catch (e: IdentityUnavailableException) {
            PairResult.Failed(IDENTITY_UNAVAILABLE_MESSAGE)
        } catch (e: HostStatusException) {
            PairResult.Failed("O PC recusou o pareamento. ${e.message}")
        } catch (e: IOException) {
            PairResult.Failed("$CONNECT_FAILED_PREFIX: ${e.message}")
        } catch (e: GeneralSecurityException) {
            PairResult.Failed("Falha criptográfica no pareamento: ${e.message}")
        } catch (e: IllegalArgumentException) {
            PairResult.Failed("Resposta inválida do PC: ${e.message}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // e.g. AndroidKeyStore ProviderException/IllegalStateException while creating or loading the identity
            PairResult.Failed("Erro interno no pareamento: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun doPair(addr: String, info: ServerInfo, otp: String, passphrase: String, expected: String): PairResult {
        val hash = PairingCrypto.hashFor(info.appVersion)
        val id = certs.identity()

        // Generate a salt for hashing the PIN; combine salt and PIN into the AES key
        val salt = PairingCrypto.random(16)
        val saltHex = hex(salt)
        val aesKey = PairingCrypto.aesKey(hash, salt, otp)

        fun cmd(vararg kv: Pair<String, String>) =
            httpGet(addr, "pair", listOf("devicename" to deviceName, "updateState" to "1") + kv)

        // Apollo keeps a failed half-done session keyed by our uniqueid, and the next getservercert
        // would hit "Out of order" after burning the OTP. Any out-of-order/short request makes the host
        // drop the session (fail_pair), so send one when we bail out mid-handshake.
        fun abort() {
            runCatching { cmd("clientpairingsecret" to "00") }
        }

        // Once getservercert is sent, Apollo holds a half-done session for our uniqueid: any exception
        // (timeout, host error, malformed reply) must reset it too, or the next QR's OTP is burned by
        // "Out of order". An abort with no session on the host is harmless ("Invalid uniqueid").
        try {
            // Send the salt (+ OTP auth) and get the server cert
            val getCert = try {
                cmd(
                    "phrase" to "getservercert",
                    "salt" to saltHex,
                    "clientcert" to hex(id.held.certificatePem().toByteArray(Charsets.UTF_8)),
                    "otpauth" to PairingCrypto.otpAuth(otp, saltHex, passphrase),
                )
            } catch (e: HostStatusException) {
                // 503 "OTP auth not available.": no OTP pending (expired or already used)
                if (e.code == 503) return PairResult.WrongPin
                throw e
            }
            if (getCert.text("paired") != "1") {
                abort()
                return PairResult.Failed("O PC recusou o pareamento")
            }
            val plain = getCert.text("plaincert")
            if (plain.isNullOrEmpty()) {
                // Another client is pairing at the same time
                abort()
                return PairResult.Failed("Outro aparelho está pareando com este PC. Tente de novo.")
            }
            val serverCert = PairingCrypto.parseCert(unhex(plain))
            if (!sameFingerprint(PairingCrypto.sha256Hex(serverCert.encoded), expected)) {
                abort()
                return PairResult.CertMismatch
            }

            // Generate a random challenge and encrypt it with our AES key
            val randomChallenge = PairingCrypto.random(16)
            val challengeResp = cmd("clientchallenge" to hex(PairingCrypto.encrypt(randomChallenge, aesKey)))
            if (challengeResp.text("paired") != "1") {
                abort()
                return PairResult.Failed("O PC interrompeu o pareamento")
            }

            // Decode the server's response and subsequent challenge
            val dec = PairingCrypto.decrypt(unhex(challengeResp.require("challengeresponse")), aesKey)
            require(dec.size >= hash.length + 16) { "challengeresponse curto" }
            val serverResponse = dec.copyOfRange(0, hash.length)
            val serverChallenge = dec.copyOfRange(hash.length, hash.length + 16)

            // Using another 16-byte secret, compute a challenge response hash using the secret, our cert sig, and the challenge
            val clientSecret = PairingCrypto.random(16)
            val challengeRespHash = hash.digest(serverChallenge + id.certificate.signature + clientSecret)
            val secretResp = cmd("serverchallengeresp" to hex(PairingCrypto.encrypt(challengeRespHash, aesKey)))
            if (secretResp.text("paired") != "1") {
                abort()
                return PairResult.Failed("O PC interrompeu o pareamento")
            }

            // Get the server's signed secret
            val serverSecretResp = unhex(secretResp.require("pairingsecret"))
            require(serverSecretResp.size > 16) { "pairingsecret curto" }
            val serverSecret = serverSecretResp.copyOfRange(0, 16)
            val serverSignature = serverSecretResp.copyOfRange(16, serverSecretResp.size)

            // Ensure the authenticity of the data
            if (!PairingCrypto.verify(serverSecret, serverSignature, serverCert)) {
                abort()
                return PairResult.Failed("A assinatura do PC não confere") // looks like a MITM
            }

            // Ensure the server challenge matched what we expected (i.e. the PIN was correct)
            val serverChallengeRespHash = hash.digest(randomChallenge + serverCert.signature + serverSecret)
            if (!MessageDigest.isEqual(serverChallengeRespHash, serverResponse)) {
                abort()
                return PairResult.WrongPin // with OTP: wrong PIN/passphrase, the host used a random PIN
            }

            // Send the server our signed secret
            val clientPairingSecret = clientSecret + PairingCrypto.sign(clientSecret, id.held.keyPair.private)
            val clientSecretResp = cmd("clientpairingsecret" to hex(clientPairingSecret))
            if (clientSecretResp.text("paired") != "1") return PairResult.Failed("O PC rejeitou o pareamento")

            // Do the initial challenge over HTTPS (seems necessary for us to show as paired)
            val pairChallenge = httpsGet(
                addr, info, expected, "pair",
                listOf("devicename" to deviceName, "updateState" to "1", "phrase" to "pairchallenge"),
            )
            if (pairChallenge.text("paired") != "1") return PairResult.Failed("O PC não confirmou o pareamento")

            certs.pin(info.uniqueId, expected)
            return PairResult.Ok
        } catch (e: Exception) {
            abort()
            throw e
        }
    }

    private fun appList(addr: String, info: ServerInfo, pin: String): List<HostApp> =
        httpsGet(addr, info, pin, "applist").elements("App").mapNotNull { app ->
            val id = app.child("ID")?.toIntOrNull() ?: return@mapNotNull null
            val title = app.child("AppTitle") ?: return@mapNotNull null
            HostApp(id, title, app.child("IsHdrSupported") == "1")
        }

    private data class PairedEndpoint(val addr: String, val info: ServerInfo, val pin: String)

    private fun paired(): PairedEndpoint {
        val (addr, info) = resolve()
        val pin = certs.pinnedHostCert(info.uniqueId) ?: throw NotPairedException(info.uniqueId)
        return PairedEndpoint(addr, info, pin)
    }

    /** First address (in order) whose HTTP serverinfo answers. */
    private fun resolve(): Pair<String, ServerInfo> {
        val last = lastAddr
        val ordered = if (last != null && last in addrs) listOf(last) + (addrs - last) else addrs
        val errors = ArrayList<String>()
        for (a in ordered) {
            try {
                val info = parseServerInfo(httpGet(a, "serverinfo"), overPinnedHttps = false)
                if (expectedUniqueId != null && info.uniqueId != expectedUniqueId) {
                    errors += "$a: é outro PC"
                    continue
                }
                lastAddr = a
                return a to info
            } catch (e: IOException) {
                errors += "$a: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IOException("nenhum endereço respondeu (${errors.joinToString("; ")})")
    }

    private fun url(scheme: String, addr: String, port: Int, path: String, query: List<Pair<String, String>>): HttpUrl {
        val b = HttpUrl.Builder().scheme(scheme).host(addr).port(port).addPathSegment(path)
        for ((k, v) in query) b.addQueryParameter(k, v)
        return b.addQueryParameter("uniqueid", certs.clientUniqueId())
            .addQueryParameter("uuid", UUID.randomUUID().toString())
            .build()
    }

    private fun httpGet(addr: String, path: String, query: List<Pair<String, String>> = emptyList()) =
        execute(base, url("http", addr, port, path, query))

    private fun httpsGet(
        addr: String,
        info: ServerInfo,
        pin: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        readTimeoutMs: Long = READ_TIMEOUT_MS,
    ): HostXml {
        val tm = PinnedTrustManager(pin)
        val ctx = SSLContext.getInstance("TLS").apply {
            init(arrayOf(ClientKeyManager(certs.identity())), arrayOf(tm), SecureRandom())
        }
        val client = base.newBuilder()
            .sslSocketFactory(ctx.socketFactory, tm)
            // Hosts are reached by IP and their self-signed cert names no host: identity is the pin.
            .hostnameVerifier { _, session -> tm.matches(session) }
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
        try {
            return execute(client, url("https", addr, info.httpsPort, path, query))
        } catch (e: IOException) {
            val actual = tm.mismatch
            if (actual != null && e !is HostStatusException) throw CertChangedException(info.uniqueId, pin, actual)
            throw e
        }
    }

    /**
     * Raw call to a host route over the pinned HTTPS channel (never HTTP): e.g. `duvo/status`,
     * `actions/clipboard`. Returns (HTTP status, body bytes). Used by [app.duvo.remote.host.DuvoApi].
     */
    internal suspend fun duvoCall(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        contentType: String = "application/json",
        readTimeoutMs: Long = READ_TIMEOUT_MS,
    ): Pair<Int, ByteArray> = io {
        val (addr, info, pin) = paired()
        val tm = PinnedTrustManager(pin)
        val ctx = SSLContext.getInstance("TLS").apply {
            init(arrayOf(ClientKeyManager(certs.identity())), arrayOf(tm), SecureRandom())
        }
        val client = base.newBuilder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, session -> tm.matches(session) }
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val url = HttpUrl.Builder().scheme("https").host(addr).port(info.httpsPort).addPathSegments(path)
            .apply { for ((k, v) in query) addQueryParameter(k, v) }
            .build()
        val req = Request.Builder().url(url).apply {
            if (method == "POST") post((body ?: ByteArray(0)).toRequestBody(contentType.toMediaType())) else get()
        }.build()
        try {
            client.newCall(req).execute().use { resp -> resp.code to (resp.body?.bytes() ?: ByteArray(0)) }
        } catch (e: IOException) {
            val actual = tm.mismatch
            if (actual != null) throw CertChangedException(info.uniqueId, pin, actual)
            throw e
        }
    }

    private fun execute(client: OkHttpClient, url: HttpUrl): HostXml {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val doc = HostXml.parse(body)
            if (doc == null) {
                if (!resp.isSuccessful) throw HostStatusException(resp.code, resp.message)
                throw IOException("resposta inválida do PC")
            }
            if (doc.status != 200) throw HostStatusException(doc.status, doc.statusMessage)
            return doc
        }
    }

    /** PairStatus is only believed over pinned HTTPS: over cleartext anyone on the LAN can claim 1. */
    private fun parseServerInfo(doc: HostXml, overPinnedHttps: Boolean) = ServerInfo(
        hostname = doc.text("hostname")?.takeIf { it.isNotEmpty() } ?: "PC",
        uniqueId = doc.require("uniqueid"),
        appVersion = doc.require("appversion"),
        httpsPort = doc.text("HttpsPort")?.toIntOrNull() ?: (port - 5),
        paired = overPinnedHttps && doc.text("PairStatus") == "1",
        state = doc.text("state"),
        codecModeSupport = doc.text("ServerCodecModeSupport")?.trim()?.toLongOrNull()?.toInt() ?: 0,
        currentGame = doc.text("currentgame")?.trim()?.toIntOrNull() ?: 0,
    )

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val CONNECT_TIMEOUT_MS = 1500L
        const val READ_TIMEOUT_MS = 7000L
        const val LAUNCH_READ_TIMEOUT_MS = 30000L

        /** Apollo's REMOTE_INPUT_UUID (host/src/process.h): the input-only session. */
        const val REMOTE_INPUT_UUID = "8CB5C136-DA67-4F99-B4A1-F9CD35005CF4"

        internal fun sameFingerprint(a: String, b: String) =
            MessageDigest.isEqual(a.lowercase().toByteArray(), b.lowercase().toByteArray())
    }
}

/** Trusts exactly one certificate, identified by the SHA-256 of its DER encoding. Never trust-all. */
internal class PinnedTrustManager(private val expectedSha256: String) : X509ExtendedTrustManager() {
    /** Fingerprint of the cert that failed the pin, if any. */
    @Volatile var mismatch: String? = null
        private set

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("O PC não apresentou certificado")
        val actual = PairingCrypto.sha256Hex(leaf.encoded)
        if (!HostClient.sameFingerprint(actual, expectedSha256)) {
            mismatch = actual
            throw CertificateException("Certificado do PC não confere com o fixado")
        }
    }

    fun matches(session: SSLSession): Boolean = runCatching {
        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate ?: return false
        HostClient.sameFingerprint(PairingCrypto.sha256Hex(leaf.encoded), expectedSha256)
    }.getOrDefault(false)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("cliente apenas")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        throw CertificateException("cliente apenas")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        throw CertificateException("cliente apenas")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Presents the GameStream client identity for TLS client auth (NvHTTP's keyManager). */
internal class ClientKeyManager(private val id: ClientIdentity) : X509ExtendedKeyManager() {
    private fun alias(keyTypes: Array<out String>?) = if (keyTypes == null || "RSA" in keyTypes) ALIAS else null

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias(keyType)
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias(keyType)
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun getCertificateChain(alias: String?) = arrayOf(id.certificate)
    override fun getPrivateKey(alias: String?) = id.held.keyPair.private
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    private companion object {
        const val ALIAS = "duvo-client"
    }
}

/** Minimal reader for GameStream XML replies (`<root status_code=".."> ... </root>`). */
internal class HostXml private constructor(private val root: Element) {
    val status: Int = root.getAttribute("status_code").toLongOrNull()?.toInt() ?: -1
    val statusMessage: String? = root.getAttribute("status_message").takeIf { it.isNotEmpty() }

    fun text(tag: String): String? = root.getElementsByTagName(tag).let { if (it.length == 0) null else it.item(0).textContent }
    fun require(tag: String): String = text(tag) ?: throw IOException("campo ausente na resposta do PC: $tag")
    fun elements(tag: String): List<Element> = root.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }

    companion object {
        fun parse(body: String): HostXml? = runCatching {
            val f = DocumentBuilderFactory.newInstance()
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            f.isExpandEntityReferences = false
            val doc = f.newDocumentBuilder().parse(body.byteInputStream(Charsets.UTF_8))
            doc.documentElement?.takeIf { it.tagName == "root" }?.let(::HostXml)
        }.getOrNull()
    }
}

internal fun Element.child(tag: String): String? =
    getElementsByTagName(tag).let { if (it.length == 0) null else it.item(0).textContent }
