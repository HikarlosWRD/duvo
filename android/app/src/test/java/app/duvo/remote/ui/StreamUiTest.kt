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

package app.duvo.remote.ui

import app.duvo.remote.host.CertChangedException
import app.duvo.remote.host.HostStatusException
import app.duvo.remote.host.LaunchResult
import app.duvo.remote.host.NotPairedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class StreamUiTest {

    // R11: StreamScreen is the first screen that talks to a pinned host after pairing.
    @Test fun `certificado trocado vira o dialogo de parear de novo para aquele PC`() {
        val ui = streamFailureUi(CertChangedException("HOST-1", "aa", "bb"))
        assertEquals(StreamUi.CertChanged("HOST-1"), ui)
        assertEquals(
            "O certificado deste PC mudou. Isso acontece se o Duvo foi reinstalado no PC — ou pode ser um ataque. Parear de novo?",
            CERT_CHANGED_MESSAGE,
        )
    }

    @Test fun `aparelho nao pareado pede para ler o QR`() {
        val ui = streamFailureUi(NotPairedException("HOST-1")) as StreamUi.Error
        assertTrue(ui.message, ui.message.contains("pareado"))
        assertTrue(ui.message, ui.message.contains("QR"))
    }

    @Test fun `PC fora do ar vira erro de conexao`() {
        val ui = streamFailureUi(IOException("timeout")) as StreamUi.Error
        assertTrue(ui.message, ui.message.startsWith("Não foi possível falar com o PC"))
    }

    @Test fun `PC que recusa o launch mostra o motivo`() {
        val ui = streamFailureUi(HostStatusException(403, "Permission denied")) as StreamUi.Error
        assertTrue(ui.message, ui.message.contains("Permission denied"))
        assertEquals(StreamUi.Error("O PC não tem o app Desktop"), launchFailedUi(LaunchResult.Failed("O PC não tem o app Desktop")))
    }
}
