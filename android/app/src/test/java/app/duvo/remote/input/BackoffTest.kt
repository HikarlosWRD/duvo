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

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {
    @Test fun `backoff segue 1 2 4 8 16 30 30`() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), (1..7).map(Backoff::delayMs))
    }

    @Test fun `tentativas muito altas ficam em 30 s sem estourar`() {
        assertEquals(30000L, Backoff.delayMs(40))
        assertEquals(30000L, Backoff.delayMs(Int.MAX_VALUE))
    }

    @Test fun `tentativa zero ou negativa conta como a primeira`() {
        assertEquals(1000L, Backoff.delayMs(0))
        assertEquals(1000L, Backoff.delayMs(-3))
    }
}
