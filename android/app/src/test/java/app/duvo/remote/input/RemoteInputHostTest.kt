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
package app.duvo.remote.input

import app.duvo.remote.discovery.HostEndpoint
import app.duvo.remote.host.LaunchResult
import app.duvo.remote.host.NotPairedException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class RemoteInputHostTest {
    private class Made(val addresses: List<String>, val port: Int, val network: String?)

    @Test fun `cada tentativa reresolve a rede e os enderecos do PC`() = runBlocking {
        val endpoints = ArrayDeque(
            listOf(
                HostEndpoint("A", listOf("10.0.0.5"), 40989),
                HostEndpoint("A", listOf("10.0.0.9", "10.0.0.5"), 40989),
            ),
        )
        var nets = 0
        val made = mutableListOf<Made>()
        val host = RemoteInputHost(
            resolve = { endpoints.removeFirst() },
            network = { nets++; "net$nets" },
            client = { ep, net ->
                made += Made(ep.addresses, ep.port, net)
                object : InputHost {
                    override suspend fun launchInputOnly(w: Int, h: Int, fps: Int) = LaunchResult.Failed("x")
                }
            },
            io = EmptyCoroutineContext,
        )

        host.launchInputOnly(2400, 1080)
        host.launchInputOnly(2400, 1080)

        assertEquals(listOf("10.0.0.5"), made[0].addresses)
        assertEquals("net1", made[0].network)
        assertEquals(listOf("10.0.0.9", "10.0.0.5"), made[1].addresses)
        assertEquals("net2", made[1].network)
        assertEquals(40989, made[1].port)
    }

    @Test fun `PC esquecido termina como nao pareado sem tentar conectar`() = runBlocking {
        var clients = 0
        val host = RemoteInputHost<String>(
            resolve = { null },
            network = { "net" },
            client = { _, _ -> clients++; error("não deve criar cliente") },
            io = EmptyCoroutineContext,
        )
        try {
            host.launchInputOnly(2400, 1080)
            fail("esperava NotPairedException")
        } catch (e: NotPairedException) {
            // expected: InputSession ends with NOT_PAIRED_REASON instead of retrying forever
        }
        assertEquals(0, clients)
    }
}
