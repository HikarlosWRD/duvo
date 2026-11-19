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

package app.duvo.remote.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayNameTest {
    @Test fun `remove quebras de linha e controles`() {
        val s = safeDisplayName("PC\n\n\n(1.2.3.4)?")
        assertEquals("PC (1.2.3.4)?", s)
        assertFalse(s.contains('\n'))
    }

    @Test fun `remove formatacao invisivel`() {
        assertEquals("PC", safeDisplayName("P\u202EC\u200B"))
    }

    @Test fun `corta em 40 com reticencias`() {
        val s = safeDisplayName("a".repeat(60))
        assertEquals(40, s.length)
        assertTrue(s.endsWith("…"))
    }

    @Test fun `separadores de linha unicode nao quebram linha`() {
        val s = safeDisplayName("PC IPs: 10.0.0.5 x")
        assertFalse(s.contains(' '))
        assertFalse(s.contains(' '))
        assertFalse(s.contains('\n'))
        assertEquals("PC IPs: 10.0.0.5 x", s)
    }

    @Test fun `espacos unicode colapsam em um`() {
        assertEquals("A B C", safeDisplayName("A  　 B C"))
    }

    @Test fun `corte nao deixa surrogate solto`() {
        val s = safeDisplayName("a".repeat(38) + "😀😀😀")
        assertTrue(s.endsWith("…"))
        assertEquals(40, s.codePointCount(0, s.length))
        assertTrue(s.endsWith("😀…"))
    }

    @Test fun `nome curto fica igual`() {
        assertEquals("PC Sala", safeDisplayName("PC Sala"))
    }
}
