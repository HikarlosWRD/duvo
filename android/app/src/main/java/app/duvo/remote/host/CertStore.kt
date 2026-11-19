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

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import okhttp3.tls.HeldCertificate
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** pt-BR text shown when the stored identity exists but cannot be read; nothing is deleted. */
const val IDENTITY_UNAVAILABLE_MESSAGE =
    "Não foi possível ler a identidade deste celular no armazenamento seguro do Android. " +
        "Nada foi apagado: tente de novo. Se continuar, reinicie o celular."

/** [CertStore.Storage.loadIdentity]: the identity exists but could not be read (decrypt/keystore failure). */
class IdentityUnreadableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The stored identity exists but is unusable. It is deliberately NOT regenerated: a new identity would
 * silently unpair every host. Not an IOException, so it is never mistaken for a network failure.
 */
class IdentityUnavailableException(cause: Throwable?) : RuntimeException(IDENTITY_UNAVAILABLE_MESSAGE, cause)

/** The GameStream client identity: self-signed RSA 2048 cert + key, and the uniqueid sent to hosts. */
class ClientIdentity(val held: HeldCertificate, val uniqueId: String) {
    val certificate: X509Certificate get() = held.certificate
}

/**
 * Client identity and per-host certificate pins.
 *
 * Key storage: the GameStream client key must (a) be presented for TLS client auth by OkHttp and
 * (b) sign the pairing secret, and its self-signed cert is sent to the host during pairing. A
 * non-exportable AndroidKeyStore RSA key only works with Conscrypt TLS client auth when generated
 * with exactly the digests/paddings each TLS version asks for (NONE + PKCS1 + PSS), which varies by
 * device/API level and cannot be exercised by JVM tests. So the key pair is generated in software
 * once, and its PKCS#8 form is stored in app-private storage encrypted with a non-exportable
 * AndroidKeyStore AES-256-GCM key ([AndroidCertStorage]).
 */
class CertStore(private val storage: Storage) {
    constructor(ctx: Context) : this(AndroidCertStorage(ctx.applicationContext))

    /** Persistence backend; [loadIdentity]/[saveIdentity] carry secret material. */
    interface Storage {
        /** null only when no identity was ever saved; throws (never returns null) when it exists but is unreadable. */
        fun loadIdentity(): String?
        fun saveIdentity(blob: String)
        fun loadPin(hostId: String): String?
        fun savePin(hostId: String, sha256: String)
        fun removePin(hostId: String)
    }

    @Volatile private var cached: ClientIdentity? = null

    fun clientCert(): X509Certificate = identity().certificate

    fun clientUniqueId(): String = identity().uniqueId

    /** Lowercase hex SHA-256 of the DER host cert pinned for [hostId] (the host's GameStream uniqueid). */
    fun pinnedHostCert(hostId: String): String? = storage.loadPin(hostId)?.lowercase()

    fun pin(hostId: String, sha256: String) = storage.savePin(hostId, sha256.lowercase())

    /** Forgets the host cert pinned for [hostId] (the user chose to pair again after a cert change). */
    fun unpin(hostId: String) = storage.removePin(hostId)

    /**
     * The client identity, generated and saved only when none exists. If the stored one cannot be read
     * or decoded this throws [IdentityUnavailableException] and leaves storage untouched.
     */
    fun identity(): ClientIdentity = cached ?: synchronized(this) {
        cached ?: load().also { cached = it }
    }

    private fun load(): ClientIdentity {
        val blob = try {
            storage.loadIdentity()
        } catch (e: Exception) {
            throw IdentityUnavailableException(e)
        } ?: return generate().also { storage.saveIdentity(encode(it)) }
        return decode(blob) ?: throw IdentityUnavailableException(null)
    }

    private fun generate(): ClientIdentity {
        val now = System.currentTimeMillis()
        val held = HeldCertificate.Builder()
            .commonName("Duvo")
            .rsa2048()
            // Hosts ignore validity (Sunshine's verify callback skips NOT_YET_VALID/EXPIRED), but keep it sane.
            .validityInterval(now - TimeUnit.DAYS.toMillis(1), now + TimeUnit.DAYS.toMillis(365L * 20))
            .build()
        val id = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it) }
        return ClientIdentity(held, id)
    }

    private fun encode(i: ClientIdentity) =
        "$UID_PREFIX${i.uniqueId}\n" + i.held.certificatePem() + i.held.privateKeyPkcs8Pem()

    private fun decode(blob: String): ClientIdentity? = runCatching {
        val first = blob.substringBefore('\n')
        require(first.startsWith(UID_PREFIX))
        ClientIdentity(HeldCertificate.decode(blob.substringAfter('\n')), first.removePrefix(UID_PREFIX))
    }.getOrNull()

    private companion object {
        const val UID_PREFIX = "uniqueid:"
    }
}

/** Identity encrypted with an AndroidKeyStore AES-GCM key in app-private files; pins in SharedPreferences. */
class AndroidCertStorage(private val ctx: Context) : CertStore.Storage {
    private val file = AtomicFile(File(File(ctx.filesDir, "duvo"), "client-identity.bin"))
    private val pins = ctx.getSharedPreferences("duvo_host_pins", Context.MODE_PRIVATE)

    override fun loadIdentity(): String? {
        if (!file.baseFile.exists()) return null // never saved: the caller generates one
        return try {
            val data = file.readFully()
            val key = existingWrapKey() ?: throw IdentityUnreadableException("chave do AndroidKeyStore ausente")
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, IV_LEN))
            String(cipher.doFinal(data, IV_LEN, data.size - IV_LEN), Charsets.UTF_8)
        } catch (e: IdentityUnreadableException) {
            throw e
        } catch (e: Exception) {
            // Unreadable (keystore/decrypt failure): report it; the file is kept so nothing is lost.
            throw IdentityUnreadableException("identidade ilegível: ${e.javaClass.simpleName}", e)
        }
    }

    override fun saveIdentity(blob: String) {
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        val out = cipher.iv + cipher.doFinal(blob.toByteArray(Charsets.UTF_8))
        file.baseFile.parentFile?.mkdirs()
        val s = file.startWrite()
        try {
            s.write(out)
            file.finishWrite(s)
        } catch (e: Exception) {
            file.failWrite(s)
            throw e
        }
    }

    override fun loadPin(hostId: String): String? = pins.getString(hostId, null)

    override fun savePin(hostId: String, sha256: String) {
        pins.edit().putString(hostId, sha256).apply()
    }

    override fun removePin(hostId: String) {
        pins.edit().remove(hostId).apply()
    }

    private fun existingWrapKey(): SecretKey? =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(KEY_ALIAS, null) as? SecretKey

    private fun wrapKey(): SecretKey {
        existingWrapKey()?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "duvo_client_identity_wrap"
        const val AES_MODE = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
