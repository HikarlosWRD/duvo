/*
 * Duvo - remote control and streaming for a Windows PC.
 * Copyright (C) 2026 Duvo contributors
 *
 * Portions ported from Moonlight Android's PairingManager.java
 * (https://github.com/moonlight-stream/moonlight-android, GPLv3, Copyright (C) Moonlight contributors)
 * and the OTP extension from Artemis (https://github.com/ClassicOldSong/moonlight-android, GPLv3).
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

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Crypto primitives of the GameStream pairing handshake (see PairingManager in Moonlight). */
object PairingCrypto {
    private val rnd = SecureRandom()

    /** Pairing hash: SHA-256 for server generation 7+ (Sunshine/Apollo report 7.1.431.-1), SHA-1 before. */
    class Hash(val algorithm: String, val length: Int) {
        fun digest(data: ByteArray): ByteArray = MessageDigest.getInstance(algorithm).digest(data)
    }

    fun hashFor(appVersion: String): Hash {
        val major = appVersion.substringBefore('.').toIntOrNull() ?: 7
        return if (major >= 7) Hash("SHA-256", 32) else Hash("SHA-1", 20)
    }

    fun random(n: Int) = ByteArray(n).also(rnd::nextBytes)

    /** AES-128 key = first 16 bytes of hash(salt || pin). */
    fun aesKey(hash: Hash, salt: ByteArray, pin: String): ByteArray = hash.digest(salt + pin.toByteArray(Charsets.UTF_8)).copyOf(16)

    /**
     * Apollo OTP auth: uppercase hex of SHA-256(pin + saltHex + passphrase), where saltHex is the exact
     * `salt` query value sent. Host side: nvhttp.cpp `util::hex(crypto::hash(pin + salt + passphrase), true)`
     * (forward byte order, 'A'-'F' alphabet) compared verbatim with the `otpauth` argument.
     */
    fun otpAuth(pin: String, saltHex: String, passphrase: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest((pin + saltHex + passphrase).toByteArray(Charsets.UTF_8)))

    /** AES/ECB without padding over the input zero-extended to a block multiple (as GameStream does). */
    fun encrypt(data: ByteArray, key: ByteArray) = aes(Cipher.ENCRYPT_MODE, data, key)
    fun decrypt(data: ByteArray, key: ByteArray) = aes(Cipher.DECRYPT_MODE, data, key)

    private fun aes(mode: Int, data: ByteArray, key: ByteArray): ByteArray {
        val padded = data.copyOf((data.size + 15) / 16 * 16)
        return Cipher.getInstance("AES/ECB/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"))
            doFinal(padded)
        }
    }

    private fun signatureFor(algorithm: String) = when (algorithm) {
        "RSA" -> Signature.getInstance("SHA256withRSA")
        "EC" -> Signature.getInstance("SHA256withECDSA")
        else -> throw IllegalArgumentException("Algoritmo de chave não suportado: $algorithm")
    }

    fun sign(data: ByteArray, key: PrivateKey): ByteArray = signatureFor(key.algorithm).run {
        initSign(key)
        update(data)
        sign()
    }

    fun verify(data: ByteArray, signature: ByteArray, cert: X509Certificate): Boolean =
        signatureFor(cert.publicKey.algorithm).run {
            initVerify(cert.publicKey)
            update(data)
            runCatching { verify(signature) }.getOrDefault(false)
        }

    fun parseCert(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private val HEX = "0123456789ABCDEF".toCharArray()

    fun hex(b: ByteArray): String {
        val out = CharArray(b.size * 2)
        for (i in b.indices) {
            val v = b[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "Hex com tamanho ímpar" }
        return ByteArray(s.length / 2) {
            val hi = Character.digit(s[it * 2], 16)
            val lo = Character.digit(s[it * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Hex inválido" }
            ((hi shl 4) or lo).toByte()
        }
    }
}
