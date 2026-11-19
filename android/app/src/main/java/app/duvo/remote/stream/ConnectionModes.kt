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
package app.duvo.remote.stream

/**
 * Stream mode and remote-control mode share the one moonlight-common-c connection of [MoonlightBridge]:
 * entering one stops whatever the other left running. Process-wide (one instance in DuvoApp).
 */
class ConnectionModes {
    private var owner: Any? = null
    private var stopOwner: (() -> Unit)? = null

    /** [newOwner] is about to start a connection; the previous owner's [stop] runs first (outside the lock). */
    fun enter(newOwner: Any, stop: () -> Unit) {
        val previous = synchronized(this) {
            if (owner === newOwner) {
                stopOwner = stop
                return
            }
            val p = stopOwner
            owner = newOwner
            stopOwner = stop
            p
        }
        previous?.invoke()
    }

    /** [o] stopped on its own (screen closed); a no-op when another owner already took over. */
    @Synchronized
    fun leave(o: Any) {
        if (owner !== o) return
        owner = null
        stopOwner = null
    }
}
