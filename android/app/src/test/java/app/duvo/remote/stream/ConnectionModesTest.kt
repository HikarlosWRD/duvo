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

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionModesTest {
    @Test fun `entrar num modo para o outro`() {
        val modes = ConnectionModes()
        val stopped = mutableListOf<String>()
        val stream = Any()
        val remote = Any()
        modes.enter(stream) { stopped += "stream" }
        modes.enter(remote) { stopped += "remote" }
        assertEquals(listOf("stream"), stopped)
        modes.enter(stream) { stopped += "stream2" }
        assertEquals(listOf("stream", "remote"), stopped)
    }

    @Test fun `reentrar no mesmo dono nao para nada`() {
        val modes = ConnectionModes()
        var stops = 0
        val owner = Any()
        modes.enter(owner) { stops++ }
        modes.enter(owner) { stops++ }
        assertEquals(0, stops)
    }

    @Test fun `sair so vale para o dono atual`() {
        val modes = ConnectionModes()
        val stopped = mutableListOf<String>()
        val a = Any()
        val b = Any()
        modes.enter(a) { stopped += "a" }
        modes.enter(b) { stopped += "b" }
        modes.leave(a) // a was already replaced: must not drop b
        modes.enter(Any()) { stopped += "c" }
        assertEquals(listOf("a", "b"), stopped)

        val m2 = ConnectionModes()
        m2.enter(a) { stopped += "x" }
        m2.leave(a)
        m2.enter(b) {}
        assertEquals(listOf("a", "b"), stopped) // a left cleanly: nothing to stop
    }
}
