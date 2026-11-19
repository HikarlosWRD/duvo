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

import app.duvo.remote.ui.StreamUi
import app.duvo.remote.ui.streamFailureUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CertStoreTest {
    /** Storage whose identity exists but cannot be read (e.g. the AndroidKeyStore wrap key is gone). */
    private class UnreadableStorage(private val inner: InMemoryCertStorage = InMemoryCertStorage()) :
        CertStore.Storage by inner {
        var saves = 0
        override fun loadIdentity(): String? = throw IdentityUnreadableException("AEADBadTagException")
        override fun saveIdentity(blob: String) { saves++; inner.saveIdentity(blob) }
    }

    @Test fun `arquivo ausente gera identidade e a salva uma vez`() {
        val storage = InMemoryCertStorage()
        val id = CertStore(storage).identity()
        assertNotNull(storage.loadIdentity())
        assertEquals(id.uniqueId, CertStore(storage).identity().uniqueId)
    }

    @Test fun `falha ao decifrar nao sobrescreve a identidade e avisa em pt-BR`() {
        val storage = UnreadableStorage()
        try {
            CertStore(storage).identity()
            fail("deveria falhar")
        } catch (e: IdentityUnavailableException) {
            assertEquals(IDENTITY_UNAVAILABLE_MESSAGE, e.message)
        }
        assertEquals(0, storage.saves)
    }

    @Test fun `identidade corrompida nao e substituida por uma nova`() {
        val storage = InMemoryCertStorage().apply { saveIdentity("lixo") }
        try {
            CertStore(storage).identity()
            fail("deveria falhar")
        } catch (e: IdentityUnavailableException) {
            assertEquals(IDENTITY_UNAVAILABLE_MESSAGE, e.message)
        }
        assertEquals("lixo", storage.loadIdentity())
    }

    @Test fun `pareamento com identidade ilegivel vira Failed com a mensagem e sem diagnostico de rede`() = runBlocking {
        val storage = UnreadableStorage()
        // No network is touched: the uniqueid is needed to build the very first URL.
        val r = HostClient(listOf("127.0.0.1"), 1, CertStore(storage)).pair("1234", "p".repeat(24), "a".repeat(64))
        assertEquals(PairResult.Failed(IDENTITY_UNAVAILABLE_MESSAGE), r)
        assertFalse((r as PairResult.Failed).reason.startsWith(CONNECT_FAILED_PREFIX))
        assertEquals(0, storage.saves)
    }

    @Test fun `transmissao com identidade ilegivel mostra a mensagem`() {
        val ui = streamFailureUi(IdentityUnavailableException(IdentityUnreadableException("x")))
        assertEquals(StreamUi.Error(IDENTITY_UNAVAILABLE_MESSAGE), ui)
        assertTrue(IDENTITY_UNAVAILABLE_MESSAGE.contains("Nada foi apagado"))
    }
}
