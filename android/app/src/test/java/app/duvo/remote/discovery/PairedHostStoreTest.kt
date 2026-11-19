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

package app.duvo.remote.discovery

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InMemoryPairedHostStore(initial: List<PairedHost> = emptyList()) : PairedHostStore {
    val items = initial.toMutableList()
    override fun load(): List<PairedHost> = items.toList()
    override fun upsert(h: PairedHost) { items.removeAll { it.uniqueId == h.uniqueId }; items += h }
    override fun remove(uniqueId: String) { items.removeAll { it.uniqueId == uniqueId } }
    override fun touch(uniqueId: String, nowMs: Long) {
        val i = items.indexOfFirst { it.uniqueId == uniqueId }
        if (i >= 0) items[i] = items[i].copy(lastUsedEpochMs = nowMs)
    }
}

class PairedHostStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun host(uid: String, used: Long = 0, ips: List<String> = listOf("10.0.0.1")) =
        PairedHost(uid, "PC $uid", ips, 40989, used)

    @Test fun `upsert e load sobrevivem a nova instancia`() {
        val f = File(tmp.root, "paired_hosts.json")
        val a = FilePairedHostStore(f)
        a.upsert(host("A", 5, listOf("1.1.1.1", "2.2.2.2")))
        a.upsert(host("B", 9))
        a.upsert(host("A", 7))
        val b = FilePairedHostStore(f).load()
        assertEquals(listOf(host("A", 7), host("B", 9)), b.sortedBy { it.uniqueId })
    }

    @Test fun `arquivo corrompido vira lista vazia sem apagar arquivo`() {
        val f = File(tmp.root, "paired_hosts.json")
        f.writeText("{x")
        assertEquals(emptyList<PairedHost>(), FilePairedHostStore(f).load())
        assertTrue(f.exists())
        assertEquals("{x", f.readText())
    }

    @Test fun `remove e touch`() {
        val s = FilePairedHostStore(File(tmp.root, "p.json"))
        s.upsert(host("A", 1)); s.upsert(host("B", 2))
        s.touch("A", 99)
        s.remove("B")
        assertEquals(listOf(host("A", 99)), s.load())
    }

    @Test fun `lastUsed devolve o de maior lastUsedEpochMs`() {
        val d = HostDiscovery(NoNsd, InMemoryPairedHostStore(listOf(host("A", 5), host("B", 50), host("C", 7))))
        assertEquals("B", d.lastUsed()?.uniqueId)
        assertNull(HostDiscovery(NoNsd, InMemoryPairedHostStore()).lastUsed())
    }

    private object NoNsd : NsdBrowser {
        override fun start(onResolved: (NsdService) -> Unit, onLost: (String) -> Unit) {}
        override fun stop() {}
    }
}
