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

import app.duvo.remote.pairing.PairPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HostDiscoveryTest {
    private class FakeNsd : NsdBrowser {
        var listener: ((NsdService) -> Unit)? = null
        var lost: ((String) -> Unit)? = null
        var started = false
        override fun start(onResolved: (NsdService) -> Unit, onLost: (String) -> Unit) {
            listener = onResolved; lost = onLost; started = true
        }
        override fun stop() { started = false }
    }

    private fun payload(ips: List<String>, name: String = "PC Sala", port: Int = 40989) =
        PairPayload(ips, port, "1234", "p".repeat(24), "a".repeat(64), name)

    @Test fun `mesmo host via mDNS e QR vira uma entrada so`() {
        val nsd = FakeNsd()
        val d = HostDiscovery(nsd, InMemoryPairedHostStore())
        d.start()
        nsd.listener!!(NsdService("pc sala", listOf("192.168.0.10"), 40989))
        d.addFromQr(payload(listOf("192.168.0.10", "192.168.0.11")))
        val hosts = d.hosts.value
        assertEquals(1, hosts.size)
        assertEquals(setOf("192.168.0.10", "192.168.0.11"), hosts[0].addresses.toSet())
    }

    @Test fun `mesmo host por IP sobreposto com nomes diferentes vira uma entrada`() {
        val nsd = FakeNsd()
        val d = HostDiscovery(nsd, InMemoryPairedHostStore())
        d.start()
        nsd.listener!!(NsdService("outro", listOf("192.168.0.11"), 40989))
        d.addFromQr(payload(listOf("192.168.0.10", "192.168.0.11"), port = 41000))
        assertEquals(1, d.hosts.value.size)
        assertEquals(41000, d.hosts.value[0].port)
    }

    @Test fun `mesmo nome com IPs disjuntos nao mescla`() {
        // A name is not an identity: another PC (or a crafted QR/deep link) may reuse it.
        val nsd = FakeNsd()
        val d = HostDiscovery(nsd, InMemoryPairedHostStore())
        d.start()
        nsd.listener!!(NsdService("PC Sala", listOf("192.168.0.10"), 40989))
        d.addFromQr(payload(listOf("10.0.0.66"), name = "PC Sala", port = 41000))
        val hosts = d.hosts.value
        assertEquals(2, hosts.size)
        assertEquals(listOf("192.168.0.10"), hosts.single { it.source == Source.MDNS }.addresses)
        assertEquals(40989, hosts.single { it.source == Source.MDNS }.port)
        assertEquals(listOf("10.0.0.66"), hosts.single { it.source == Source.QR }.addresses)
    }

    @Test fun `markPaired marca a entrada e sobrevive a novos merges`() {
        val nsd = FakeNsd()
        val d = HostDiscovery(nsd, InMemoryPairedHostStore())
        d.start()
        d.addFromQr(payload(listOf("192.168.0.10")))
        assertEquals(false, d.hosts.value.single().paired)
        d.markPaired(payload(listOf("192.168.0.10")))
        assertEquals(true, d.hosts.value.single().paired)
        nsd.listener!!(NsdService("PC Sala", listOf("192.168.0.10"), 40989))
        assertEquals(true, d.hosts.value.single().paired)
    }

    @Test fun `IP manual sem porta usa 40989`() {
        val d = HostDiscovery(FakeNsd(), InMemoryPairedHostStore())
        d.addManual("10.0.0.5")
        val h = d.hosts.value.single()
        assertEquals(40989, h.port)
        assertEquals(listOf("10.0.0.5"), h.addresses)
        assertEquals(Source.MANUAL, h.source)
    }

    @Test fun `hosts distintos ficam separados`() {
        val nsd = FakeNsd()
        val d = HostDiscovery(nsd, InMemoryPairedHostStore())
        d.start()
        nsd.listener!!(NsdService("A", listOf("10.0.0.1"), 40989))
        nsd.listener!!(NsdService("B", listOf("10.0.0.2"), 40989))
        assertEquals(2, d.hosts.value.size)
    }

    @Test fun `lost remove entrada so mDNS`() {
        val nsd = FakeNsd()
        val d = HostDiscovery(nsd, InMemoryPairedHostStore())
        d.start()
        nsd.listener!!(NsdService("PC", listOf("10.0.0.1"), 40989))
        nsd.lost!!("pc")
        assertEquals(0, d.hosts.value.size)
    }

    @Test fun `lost em entrada mesclada mantem sem a fonte mDNS`() {
        val nsd = FakeNsd()
        val d = HostDiscovery(nsd, InMemoryPairedHostStore())
        d.start()
        nsd.listener!!(NsdService("PC Sala", listOf("10.0.0.1"), 40989))
        d.addFromQr(payload(listOf("10.0.0.1"), name = "PC Sala"))
        nsd.lost!!("PC Sala")
        val h = d.hosts.value.single()
        assertEquals(setOf(Source.QR), h.sources)
        assertEquals(Source.QR, h.source)
    }

    @Test fun `stop remove entradas so mDNS e preserva manuais`() {
        val nsd = FakeNsd()
        val d = HostDiscovery(nsd, InMemoryPairedHostStore())
        d.start()
        nsd.listener!!(NsdService("A", listOf("10.0.0.1"), 40989))
        d.addManual("10.0.0.9")
        d.stop()
        assertEquals(listOf("10.0.0.9"), d.hosts.value.map { it.addresses.single() })
    }

    @Test fun `holder devolve sempre a mesma instancia`() {
        val holder = DiscoveryHolder { HostDiscovery(FakeNsd(), InMemoryPairedHostStore()) }
        assertSame(holder.instance, holder.instance)
    }

    private fun paired(uid: String, vararg ips: String) = PairedHost(uid, "PC Sala", ips.toList(), 40989, 1)

    @Test fun `hosts semeados com pareados marcados paired`() {
        val d = HostDiscovery(FakeNsd(), InMemoryPairedHostStore(listOf(paired("A", "1.1.1.1"))))
        val h = d.hosts.value.single()
        assertEquals(true, h.paired)
        assertEquals("A", h.uniqueId)
        assertEquals(listOf("1.1.1.1"), h.addresses)
        assertEquals(Source.QR, h.source)
    }

    @Test fun `merge por uniqueId mesmo com IPs diferentes`() {
        val nsd = FakeNsd()
        val d = HostDiscovery(nsd, InMemoryPairedHostStore(listOf(paired("A", "1.1.1.1"))))
        d.start()
        d.mergeResolved(DiscoveredHost("PC Sala", listOf("2.2.2.2"), 40989, Source.MDNS, uniqueId = "A"))
        val h = d.hosts.value.single()
        assertEquals(setOf("1.1.1.1", "2.2.2.2"), h.addresses.toSet())
        assertEquals(true, h.paired)
    }

    @Test fun `uniqueIds diferentes com IP sobreposto nao mesclam`() {
        val d = HostDiscovery(FakeNsd(), InMemoryPairedHostStore(listOf(paired("A", "1.1.1.1"))))
        d.mergeResolved(DiscoveredHost("Outro", listOf("1.1.1.1"), 40989, Source.MDNS, uniqueId = "B"))
        assertEquals(2, d.hosts.value.size)
    }

    @Test fun `mesmo nome IPs disjuntos sem uniqueId nao mescla com pareado`() {
        val nsd = FakeNsd()
        val d = HostDiscovery(nsd, InMemoryPairedHostStore(listOf(paired("A", "1.1.1.1"))))
        d.start()
        nsd.listener!!(NsdService("PC Sala", listOf("9.9.9.9"), 40989))
        assertEquals(2, d.hosts.value.size)
    }

    @Test fun `markPaired com uniqueId persiste e lastUsed reflete`() {
        val store = InMemoryPairedHostStore()
        val d = HostDiscovery(FakeNsd(), store, clock = { 1234L })
        d.markPaired(payload(listOf("10.0.0.1")), "UID")
        assertEquals("UID", store.items.single().uniqueId)
        assertEquals(1234L, d.lastUsed()?.lastUsedEpochMs)
        assertEquals("UID", d.hosts.value.single().uniqueId)
    }

    @Test fun `markPaired sem uniqueId nao persiste`() {
        val store = InMemoryPairedHostStore()
        val d = HostDiscovery(FakeNsd(), store)
        d.markPaired(payload(listOf("10.0.0.1")))
        assertEquals(true, d.hosts.value.single().paired)
        assertEquals(0, store.items.size)
    }

    @Test fun `parear B no mesmo IP remove A persistido da loja, memoria e pin`() {
        val store = InMemoryPairedHostStore(listOf(paired("A", "10.0.0.1")))
        val unpinned = mutableListOf<String>()
        val d = HostDiscovery(FakeNsd(), store, clock = { 5L }, unpin = { unpinned += it })
        d.markPaired(payload(listOf("10.0.0.1")), "B")
        assertEquals(listOf("B"), store.items.map { it.uniqueId })
        assertEquals(listOf("B"), d.hosts.value.map { it.uniqueId })
        assertEquals(listOf("A"), unpinned)
    }

    @Test fun `forget remove da loja, memoria e limpa pin`() {
        val store = InMemoryPairedHostStore(listOf(paired("A", "10.0.0.1"), paired("B", "10.0.0.2")))
        val unpinned = mutableListOf<String>()
        val d = HostDiscovery(FakeNsd(), store, unpin = { unpinned += it })
        d.forget("A")
        assertEquals(listOf("B"), store.items.map { it.uniqueId })
        assertEquals(listOf("B"), d.hosts.value.map { it.uniqueId })
        assertEquals(listOf("A"), unpinned)
    }

    @Test fun `mDNS sem uniqueId com IP em duas entradas mescla na pareada mais recente`() {
        val nsd = FakeNsd()
        val old = paired("A", "10.0.0.1").copy(lastUsedEpochMs = 1)
        val recent = paired("B", "10.0.0.1").copy(lastUsedEpochMs = 99)
        val d = HostDiscovery(nsd, InMemoryPairedHostStore(listOf(old, recent)))
        d.start()
        nsd.listener!!(NsdService("PC Sala", listOf("10.0.0.1", "10.0.0.9"), 40989))
        val hosts = d.hosts.value
        assertEquals(2, hosts.size)
        assertEquals(true, hosts.single { it.uniqueId == "B" }.addresses.contains("10.0.0.9"))
        assertEquals(false, hosts.single { it.uniqueId == "A" }.addresses.contains("10.0.0.9"))
    }

    @Test fun `pareado mostra o nome salvo no pareamento e nao o hostname do mDNS`() {
        val nsd = FakeNsd()
        val store = InMemoryPairedHostStore()
        val d = HostDiscovery(nsd, store)
        d.start()
        nsd.listener!!(NsdService("DESKTOP-AB12CD", listOf("192.168.0.10"), 40989))
        d.addFromQr(payload(listOf("192.168.0.10"), name = "PC Sala"))
        d.markPaired(payload(listOf("192.168.0.10"), name = "PC Sala"), "UID")
        assertEquals("PC Sala", d.hosts.value.single().name)
        assertEquals("PC Sala", store.items.single().name)
        // mDNS seen again later (or a restart: the persisted entry comes first) keeps the paired name.
        nsd.listener!!(NsdService("DESKTOP-AB12CD", listOf("192.168.0.10"), 40989))
        assertEquals("PC Sala", d.hosts.value.single().name)
    }

    @Test fun `endpointFor junta enderecos da memoria, da loja e do mDNS atual`() {
        val nsd = FakeNsd()
        val d = HostDiscovery(nsd, InMemoryPairedHostStore(listOf(paired("A", "10.0.0.1"))))
        d.start()
        d.mergeResolved(DiscoveredHost("PC Sala", listOf("10.0.0.7"), 40989, Source.MDNS, uniqueId = "A"))
        // The PC changed IP: mDNS reports it under its Windows name, with no uniqueid and no shared address.
        nsd.listener!!(NsdService("DESKTOP-AB12CD", listOf("10.0.0.42"), 40989))
        nsd.listener!!(NsdService("Outra porta", listOf("10.0.0.50"), 47989))

        val ep = d.endpointFor("A")!!

        assertEquals("A", ep.uniqueId)
        assertEquals(40989, ep.port)
        assertEquals(listOf("10.0.0.1", "10.0.0.7", "10.0.0.42"), ep.addresses)
    }

    @Test fun `endpointFor de PC esquecido ou desconhecido e null`() {
        val d = HostDiscovery(FakeNsd(), InMemoryPairedHostStore(listOf(paired("A", "10.0.0.1"))))
        d.forget("A")
        assertEquals(null, d.endpointFor("A"))
        assertEquals(null, d.endpointFor("nunca"))
    }
}
