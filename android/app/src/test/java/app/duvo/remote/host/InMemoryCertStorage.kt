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

/** JVM-test stand-in for the Android Keystore-backed storage. */
class InMemoryCertStorage : CertStore.Storage {
    private var identity: String? = null
    val pins = HashMap<String, String>()

    override fun loadIdentity(): String? = identity
    override fun saveIdentity(blob: String) { identity = blob }
    override fun loadPin(hostId: String): String? = pins[hostId]
    override fun savePin(hostId: String, sha256: String) { pins[hostId] = sha256 }
    override fun removePin(hostId: String) { pins.remove(hostId) }
}
