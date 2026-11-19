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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamLaunchTest {
    @Test fun `sair da tela durante o Launching vira erro em vez de spinner infinito`() = runBlocking {
        val launch = StreamLaunch(this)
        val cancelled = CompletableDeferred<Unit>()
        assertTrue(launch.begin {
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        })
        yield() // the launch is now suspended talking to the host
        launch.stop() // surfaceDestroyed (app backgrounded)
        cancelled.await()
        assertEquals(StreamUi.Error(STREAM_INTERRUPTED_MESSAGE), launch.ui.value)
        // surfaceCreated on return must not leave the screen stuck: the error stays, no second launch.
        assertFalse(launch.begin { launch.set(StreamUi.Error("nao deveria rodar")) })
        assertEquals(StreamUi.Error(STREAM_INTERRUPTED_MESSAGE), launch.ui.value)
    }

    @Test fun `begin so roda uma vez`() = runBlocking {
        val launch = StreamLaunch(this)
        var runs = 0
        assertTrue(launch.begin { runs++ })
        assertFalse(launch.begin { runs++ })
        yield()
        assertEquals(1, runs)
    }

    @Test fun `stop depois de um resultado mantem o resultado`() = runBlocking {
        val launch = StreamLaunch(this)
        launch.begin { launch.set(StreamUi.Error("O PC não tem o app Desktop")) }
        yield()
        launch.stop()
        assertEquals(StreamUi.Error("O PC não tem o app Desktop"), launch.ui.value)
    }
}
