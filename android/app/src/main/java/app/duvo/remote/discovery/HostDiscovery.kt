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
import app.duvo.remote.pairing.QrPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Source { MDNS, QR, MANUAL }

data class DiscoveredHost(
    val name: String,
    val addresses: List<String>,
    val port: Int,
    val source: Source,
    /** Every source that has reported this host; [source] is the primary one shown in the UI. */
    val sources: Set<Source> = setOf(source),
    /** This device completed pairing with the host in this session (the pin itself lives in CertStore). */
    val paired: Boolean = false,
    /** The host's GameStream uniqueid when known (paired hosts); null for mDNS/QR/manual entries. */
    val uniqueId: String? = null,
)

/** Where to reach a paired host right now: [addresses] in the order to try them. */
data class HostEndpoint(val uniqueId: String, val addresses: List<String>, val port: Int)

class HostDiscovery(
    private val nsd: NsdBrowser,
    private val store: PairedHostStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Forgets the TLS pin kept for a host uniqueid (CertStore.unpin). */
    private val unpin: (String) -> Unit = {},
) {
    private val _hosts = MutableStateFlow(
        store.load().map {
            DiscoveredHost(it.name, it.addresses, it.port, Source.QR, paired = true, uniqueId = it.uniqueId)
        },
    )
    val hosts: StateFlow<List<DiscoveredHost>> = _hosts.asStateFlow()

    /**
     * Current candidates for paired host [uniqueId] (re-read on every reconnect): its in-memory entry
     * (which merged the addresses mDNS reports now), then the persisted ones, then every mDNS-only
     * host on the same port — a PC that changed IP shows up under its Windows name with no shared
     * address. Trying another PC is harmless: HostClient's expectedUniqueId skips it. Null when the
     * host is not (or no longer) paired.
     */
    fun endpointFor(uniqueId: String): HostEndpoint? {
        val persisted = store.load().firstOrNull { it.uniqueId == uniqueId }
        val list = _hosts.value
        val mem = list.firstOrNull { it.uniqueId == uniqueId }
        if (persisted == null && mem == null) return null
        val port = persisted?.port ?: mem!!.port
        val mdns = list.filter { it.uniqueId == null && it.port == port && Source.MDNS in it.sources }
        val addrs = (mem?.addresses.orEmpty() + persisted?.addresses.orEmpty() + mdns.flatMap { it.addresses }).distinct()
        return HostEndpoint(uniqueId, addrs, port)
    }

    /** The paired host used most recently, or null. */
    fun lastUsed(): PairedHost? = store.load().maxByOrNull { it.lastUsedEpochMs }

    /** Marks a paired PC as just used (auto-connect picks the most recent). */
    fun touch(uniqueId: String, nowMs: Long = System.currentTimeMillis()) = store.touch(uniqueId, nowMs)

    fun start() = nsd.start(
        onResolved = { s -> merge(DiscoveredHost(s.name, s.addresses, s.port, Source.MDNS)) },
        onLost = { name -> dropMdns { it.name.equals(name, ignoreCase = true) } },
    )

    fun stop() {
        nsd.stop()
        dropMdns { true }
    }

    /** Removes MDNS-only entries; merged entries stay but lose the MDNS source. */
    private fun dropMdns(match: (DiscoveredHost) -> Boolean) = _hosts.update { list ->
        list.mapNotNull { h ->
            if (!match(h) || Source.MDNS !in h.sources) return@mapNotNull h
            val rest = h.sources - Source.MDNS
            if (rest.isEmpty()) null else h.copy(source = rest.first(), sources = rest)
        }
    }

    fun addManual(ip: String, port: Int = QrPayload.DEFAULT_PORT) =
        merge(DiscoveredHost(ip, listOf(ip), port, Source.MANUAL))

    /** Only name, addresses and port are kept; OTP, passphrase and fingerprint never enter this list. */
    fun addFromQr(p: PairPayload) = merge(DiscoveredHost(p.name, p.ips, p.port, Source.QR))

    /** With [uniqueId] (from the pinned serverinfo) the host is also persisted; without it, only marked in memory. */
    fun markPaired(p: PairPayload, uniqueId: String? = null) {
        merge(DiscoveredHost(p.name, p.ips, p.port, Source.QR, paired = true, uniqueId = uniqueId))
        if (uniqueId == null) return
        val h = _hosts.value.firstOrNull { it.uniqueId == uniqueId } ?: return
        store.upsert(PairedHost(uniqueId, h.name, h.addresses, h.port, clock()))
        // A reinstalled host comes back with a new uniqueid on the same address: the old entry is stale.
        store.load()
            .filter { it.uniqueId != uniqueId && it.addresses.any { a -> a in h.addresses } }
            .forEach { forget(it.uniqueId) }
    }

    /** Removes a paired host: persisted entry, in-memory entry and its pinned certificate. */
    fun forget(uniqueId: String) {
        store.remove(uniqueId)
        unpin(uniqueId)
        _hosts.update { l -> l.filter { it.uniqueId != uniqueId } }
    }

    internal fun mergeResolved(h: DiscoveredHost) = merge(h)

    /**
     * Same host (a shared address) collapses into one entry. A name alone never merges: any PC, QR or
     * deep link can claim it, and merging would redirect a known host to another IP/port.
     * When both sides know a uniqueid it decides alone: equal ids merge even with disjoint addresses,
     * different ids never merge.
     */
    private fun merge(new: DiscoveredHost) = _hosts.update { list ->
        val lastUsed = store.load().associate { it.uniqueId to it.lastUsedEpochMs }
        // Same uniqueid wins; otherwise a shared address, unless both uniqueids are known and differ.
        // Several candidates: the paired one, then the most recently used.
        val idx = list.indices.filter { i ->
            val o = list[i]
            if (o.uniqueId != null && new.uniqueId != null) o.uniqueId == new.uniqueId
            else o.addresses.any { a -> a in new.addresses }
        }.maxWithOrNull(
            compareBy<Int>({ list[it].paired }, { lastUsed[list[it].uniqueId] ?: -1L }, { -it }),
        ) ?: -1
        if (idx < 0) return@update list + new
        val old = list[idx]
        // The QR is authoritative for port; a manual placeholder name yields to a real one.
        val qr = if (new.source == Source.QR) new else if (old.source == Source.QR) old else null
        // A paired host keeps the name the user saw in the QR (sunshine_name), not the mDNS Windows hostname.
        val name = when {
            new.paired && new.source == Source.QR -> new.name
            old.paired -> old.name
            old.source == Source.MANUAL -> new.name
            else -> old.name
        }
        val merged = DiscoveredHost(
            name = name,
            addresses = (old.addresses + new.addresses).distinct(),
            port = qr?.port ?: old.port,
            source = qr?.source ?: if (old.source == Source.MANUAL) new.source else old.source,
            sources = old.sources + new.sources,
            paired = old.paired || new.paired,
            uniqueId = old.uniqueId ?: new.uniqueId,
        )
        list.toMutableList().also { it[idx] = merged }
    }
}

/**
 * PC to open directly in Remote Control mode when the app starts: the most recently used paired PC,
 * only on a fresh launch and never when a pairing link opened the app.
 */
fun autoConnectTarget(freshLaunch: Boolean, openedByPairLink: Boolean, lastUsed: PairedHost?): String? =
    if (freshLaunch && !openedByPairLink) lastUsed?.uniqueId else null
