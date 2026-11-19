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

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * A user stop racing moonlight-common-c's detached "AsyncTerm" thread (connectionTerminated) must
 * neither crash (JNI use of a released reference) nor deliver one session's callbacks to the next.
 *
 * No host needed: the stream targets a closed port on loopback, so LiStartConnection sits in its
 * RTSP connect-retry loop (ECONNREFUSED, 500 ms sleeps) until stop() interrupts it. The AsyncTerm
 * thread is reproduced by a debug-only native hook that runs the same connectionTerminated callback
 * on a detached thread after a delay.
 */
@RunWith(AndroidJUnit4::class)
class NativeBridgeStressTest {
    private val bridge = MoonlightBridge

    private val cfg = StreamConfig(
        host = "127.0.0.1",
        width = 1280,
        height = 720,
        fps = 60,
        bitrateKbps = 10_000,
        mime = DecoderProbe.MIME_AVC,
        riKey = ByteArray(16),
        riKeyId = 0,
        rtspUrl = "rtsp://127.0.0.1:1",
        serverCodecModeSupport = 0x1, // SCM_H264; 0 trips an LC_ASSERT in debug builds
    )

    /** Records every termination this instance (one session) received. */
    private class Counting : StreamCallbacks {
        val terminations = CopyOnWriteArrayList<Int>()
        override fun connectionStarted() {}
        override fun connectionTerminated(errorCode: Int) {
            terminations += errorCode
        }
        override fun videoSetup(videoFormat: Int, width: Int, height: Int, fps: Int): Int = -1
        override fun videoSubmit(
            data: ByteArray,
            csdLength: Int,
            length: Int,
            frameNumber: Int,
            frameType: Int,
            enqueueTimeUs: Long,
        ): Int = NativeBridge.DR_OK
    }

    @After fun tearDown() {
        bridge.stop()
        awaitSimulatedTerminations()
    }

    @Test fun stopRacingConnectionTerminatedNeitherCrashesNorLeaksAcrossSessions() {
        val rnd = Random(0x0b17)
        val sessions = List(CYCLES) { Counting() }
        var fired = 0
        sessions.forEachIndexed { i, cb ->
            var ret = Int.MIN_VALUE
            val starter = thread(name = "start-$i") { ret = bridge.start(cfg, cb) }
            Thread.sleep(rnd.nextLong(5, 40)) // let LiStartConnection reach the RTSP retry loop
            // connectionTerminated of session i on its own thread, landing before, during or after stop().
            bridge.simulateAsyncTermination(codeFor(i), delayUs = rnd.nextInt(0, 700_000))
            fired++
            bridge.stop()
            starter.join(15_000)
            assertTrue("start() of cycle $i did not return", !starter.isAlive)
            assertTrue("start() of cycle $i should fail (no host), got $ret", ret != 0)
            awaitSimulatedTerminations()
        }
        sessions.forEachIndexed { i, cb ->
            assertTrue(
                "session $i got callbacks of another session: ${cb.terminations}",
                cb.terminations.all { it == codeFor(i) },
            )
            assertTrue("session $i got its termination twice", cb.terminations.size <= 1)
        }
        val delivered = sessions.sumOf { it.terminations.size }
        // The race went both ways: some terminations arrived while the session was live, some late.
        assertTrue("no termination was delivered ($delivered/$fired)", delivered > 0)
        assertTrue("no termination arrived late ($delivered/$fired)", delivered < fired)
    }

    @Test fun lateCallbackFromPreviousSessionNeverReachesNewCallbacks() {
        val old = Counting()
        val oldStarter = thread(name = "start-old") { bridge.start(cfg, old) }
        Thread.sleep(30)
        bridge.stop()
        oldStarter.join(15_000)

        val new = Counting()
        val newStarter = thread(name = "start-new") { bridge.start(cfg, new) }
        Thread.sleep(30)
        bridge.simulateAsyncTermination(-1, delayUs = 0, generationDelta = -1) // previous session's
        bridge.simulateAsyncTermination(-2, delayUs = 0) // this session's
        awaitSimulatedTerminations()
        bridge.stop()
        newStarter.join(15_000)

        assertEquals(listOf(-2), new.terminations.toList())
        assertEquals(emptyList<Int>(), old.terminations.toList())
    }

    private fun codeFor(cycle: Int) = -(10_000 + cycle)

    private fun awaitSimulatedTerminations() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (bridge.pendingSimulatedTerminations() > 0) {
            check(System.nanoTime() < deadline) { "simulated AsyncTerm threads did not finish" }
            Thread.sleep(5)
        }
    }

    private companion object {
        const val CYCLES = 50
    }
}
