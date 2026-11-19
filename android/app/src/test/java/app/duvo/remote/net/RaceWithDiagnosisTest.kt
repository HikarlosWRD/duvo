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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RaceWithDiagnosisTest {
    @Test fun `diagnostico nao OK vence e cancela a tentativa`() = runBlocking {
        val cancelled = CompletableDeferred<Boolean>()
        val r = raceWithDiagnosis<String>(
            attempt = { try { delay(60_000); "x" } finally { cancelled.complete(true) } },
            diagnose = { delay(50); Diagnosis.DIFFERENT_SUBNET_LIKELY_GUEST },
        )
        assertEquals(Raced.Diagnosed(Diagnosis.DIFFERENT_SUBNET_LIKELY_GUEST), r)
        assertTrue(withTimeout(2000) { cancelled.await() })
    }

    @Test fun `diagnostico OK devolve o resultado da tentativa`() = runBlocking {
        val r = raceWithDiagnosis(attempt = { delay(100); "ok" }, diagnose = { Diagnosis.OK })
        assertEquals(Raced.Completed("ok"), r)
    }

    @Test fun `tentativa que falha com excecao e explicada pelo diagnostico`() = runBlocking {
        val r = raceWithDiagnosis<String>(attempt = { throw IOException("x") }, diagnose = { delay(50); Diagnosis.HOST_OFF_OR_FIREWALL })
        assertEquals(Raced.Diagnosed(Diagnosis.HOST_OFF_OR_FIREWALL), r)
    }

    @Test fun `falha com diagnostico OK relanca a excecao`() {
        try {
            runBlocking { raceWithDiagnosis<String>(attempt = { throw IOException("boom") }, diagnose = { Diagnosis.OK }) }
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertEquals("boom", e.message)
        }
    }

    @Test fun `resultado marcado como falha usa o diagnostico`() = runBlocking {
        val r = raceWithDiagnosis(attempt = { "fail" }, diagnose = { delay(30); Diagnosis.NO_WIFI }, failed = { it == "fail" })
        assertEquals(Raced.Diagnosed(Diagnosis.NO_WIFI), r)
    }
}
