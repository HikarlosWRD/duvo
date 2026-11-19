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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The production [InputHost] of the remote control: every (re)connection attempt re-resolves the Wi-Fi
 * network ([network], e.g. WifiBinding lease refresh) and the host addresses ([resolve]: persisted +
 * what mDNS reports now), then launches through a fresh [client] (HostClient pinned to the uniqueid).
 * A host that is no longer paired (forgotten meanwhile) ends the session as not paired.
 */
class RemoteInputHost<N>(
    private val resolve: () -> HostEndpoint?,
    private val network: () -> N?,
    private val client: (HostEndpoint, N?) -> InputHost,
    private val io: CoroutineContext = Dispatchers.IO,
) : InputHost {
    override suspend fun launchInputOnly(w: Int, h: Int, fps: Int): LaunchResult {
        val (ep, net) = withContext(io) {
            val ep = resolve() ?: throw NotPairedException("?")
            ep to network()
        }
        return client(ep, net).launchInputOnly(w, h, fps)
    }
}
