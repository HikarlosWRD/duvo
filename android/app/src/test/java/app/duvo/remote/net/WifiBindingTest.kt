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

package app.duvo.remote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiBindingTest {
    private val binds = ArrayList<String?>()
    private var wifi: String? = "wifi"
    private var bindResult = true
    private var bindThrows = 0
    private val core = WifiBindingCore(find = { wifi }, bind = {
        if (bindThrows > 0) { bindThrows--; throw IllegalStateException("boom") }
        binds += it
        bindResult
    })

    @Test fun `prende o processo ao Wi-Fi enquanto ha uso e solta no fim`() {
        val lease = core.acquire()
        assertEquals("wifi", lease.network)
        assertEquals(listOf<String?>("wifi"), binds)
        lease.release()
        assertEquals(listOf("wifi", null), binds)
    }

    @Test fun `sem Wi-Fi nao prende nada`() {
        wifi = null
        val lease = core.acquire()
        assertNull(lease.network)
        lease.release()
        assertEquals(emptyList<String?>(), binds)
    }

    @Test fun `pareamento e transmissao sobrepostos so soltam quando o ultimo termina`() {
        val pairing = core.acquire()
        val stream = core.acquire()
        assertEquals(listOf<String?>("wifi"), binds) // bound once
        pairing.release()
        assertEquals(listOf<String?>("wifi"), binds)
        stream.release()
        assertEquals(listOf("wifi", null), binds)
    }

    @Test fun `soltar duas vezes nao desfaz o uso de outro`() {
        val a = core.acquire()
        val b = core.acquire()
        a.release()
        a.release() // onCleared after the flow already released
        assertEquals(listOf<String?>("wifi"), binds)
        b.release()
        assertEquals(listOf("wifi", null), binds)
    }

    @Test fun `Wi-Fi que aparece depois e usado no proximo uso`() {
        wifi = null
        val a = core.acquire()
        wifi = "wifi"
        val b = core.acquire()
        assertEquals("wifi", b.network)
        a.release()
        b.release()
        assertEquals(listOf("wifi", null), binds)
    }

    @Test fun `acquire nao vaza contador se bind lancar`() {
        bindThrows = 1
        try { core.acquire(); org.junit.Assert.fail() } catch (e: IllegalStateException) {}
        val lease = core.acquire()
        lease.release()
        assertEquals(listOf<String?>("wifi", null), binds)
    }

    @Test fun `bind falhando nao marca bound`() {
        bindResult = false
        val lease = core.acquire()
        assertNull(lease.network)
        lease.release()
        assertEquals(listOf<String?>("wifi"), binds) // no unbind: nothing was bound
    }

    @Test fun `rede perdida solta o bind e o proximo uso prende a nova`() {
        val a = core.acquire()
        core.onLost("wifi")
        assertEquals(listOf("wifi", null), binds)
        assertNull(a.network)
        wifi = "wifi2"
        val b = core.acquire()
        assertEquals("wifi2", b.network)
        assertEquals("wifi2", a.network) // leases see the current binding
        a.release()
        b.release()
        assertEquals(listOf("wifi", null, "wifi2", null), binds)
    }

    @Test fun `perder outra rede nao mexe no bind`() {
        val a = core.acquire()
        core.onLost("4g")
        assertEquals(listOf<String?>("wifi"), binds)
        assertEquals("wifi", a.network)
        a.release()
    }

    @Test fun `refresh reresolve o Wi-Fi a cada tentativa`() {
        val a = core.acquire()
        assertEquals("wifi", a.refresh())
        assertEquals(listOf<String?>("wifi"), binds) // same network: no rebind
        wifi = "wifi2"
        assertEquals("wifi2", a.refresh())
        assertEquals(listOf<String?>("wifi", "wifi2"), binds)
        wifi = null
        assertNull(a.refresh())
        assertEquals(listOf("wifi", "wifi2", null), binds)
        wifi = "wifi3"
        assertEquals("wifi3", a.refresh())
        a.release()
        assertEquals(listOf("wifi", "wifi2", null, "wifi3", null), binds)
    }

    @Test fun `lease sem rede passa a contar quando o Wi-Fi aparece`() {
        wifi = null
        val a = core.acquire()
        assertNull(a.network)
        wifi = "wifi"
        assertEquals("wifi", a.refresh())
        assertEquals("wifi", a.network)
        a.release()
        assertEquals(listOf("wifi", null), binds)
    }

    @Test fun `refresh depois de soltar nao prende nada`() {
        val a = core.acquire()
        a.release()
        assertNull(a.refresh())
        assertEquals(listOf("wifi", null), binds)
    }
}
