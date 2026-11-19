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
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPayloadTest {
    private val fp = "a".repeat(64)
    private val pp = "abcDEF123-_abcDEF123-_abc"

    private fun uri(
        v: String = "1", h: String = "192.168.0.10,192.168.0.11", p: String = "40989",
        otp: String = "1234", pp: String? = this.pp, fp: String = this.fp, n: String = "PC%20Sala",
    ) = "duvo://pair?v=$v&h=$h&p=$p&otp=$otp" + (if (pp != null) "&pp=$pp" else "") + "&fp=$fp&n=$n"

    @Test fun `parse URI valida com dois IPs`() {
        val r = QrPayload.parse(uri()).getOrThrow()
        assertEquals(listOf("192.168.0.10", "192.168.0.11"), r.ips)
        assertEquals(40989, r.port)
        assertEquals("1234", r.otp)
        assertEquals(pp, r.passphrase)
        assertEquals(fp, r.certSha256)
        assertEquals("PC Sala", r.name)
    }

    @Test fun `rejeita versao desconhecida`() = assertTrue(QrPayload.parse(uri(v = "2")).isFailure)

    @Test fun `rejeita fingerprint que nao tem 64 hex`() = assertTrue(QrPayload.parse(uri(fp = "xyz")).isFailure)

    @Test fun `rejeita esquema diferente de duvo`() =
        assertTrue(QrPayload.parse(uri().replace("duvo://", "https://")).isFailure)

    @Test fun `rejeita pp ausente`() = assertTrue(QrPayload.parse(uri(pp = null)).isFailure)

    @Test fun `rejeita otp que nao tem 4 digitos`() {
        assertTrue(QrPayload.parse(uri(otp = "123")).isFailure)
        assertTrue(QrPayload.parse(uri(otp = "12345")).isFailure)
        assertTrue(QrPayload.parse(uri(otp = "12a4")).isFailure)
    }

    @Test fun `decodifica nome com percent-encoding UTF-8`() =
        assertEquals("Sala São", QrPayload.parse(uri(n = "Sala%20S%C3%A3o")).getOrThrow().name)

    @Test fun `rejeita porta nao numerica ou fora da faixa`() {
        assertTrue(QrPayload.parse(uri(p = "abc")).isFailure)
        assertTrue(QrPayload.parse(uri(p = "0")).isFailure)
        assertTrue(QrPayload.parse(uri(p = "65536")).isFailure)
    }

    @Test fun `rejeita pp curto ou com caractere invalido`() {
        assertTrue(QrPayload.parse(uri(pp = "abc")).isFailure)
        assertTrue(QrPayload.parse(uri(pp = "abcDEF123-_abcDEF123-_ab!")).isFailure)
    }

    @Test fun `mais no nome continua mais`() =
        assertEquals("a+b", QrPayload.parse(uri(n = "a+b")).getOrThrow().name)

    @Test fun `percent-encoding invalido no nome falha sem lancar`() =
        assertTrue(QrPayload.parse(uri(n = "%G1")).isFailure)

    @Test fun `rejeita IPv4 invalido em h`() {
        assertTrue(QrPayload.parse(uri(h = "192.168.0.256")).isFailure)
        assertTrue(QrPayload.parse(uri(h = "192.168.0.10,abc")).isFailure)
    }

    @Test fun `fingerprint maiusculo e aceito e normalizado`() =
        assertEquals(fp, QrPayload.parse(uri(fp = fp.uppercase())).getOrThrow().certSha256)
}
