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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeBridgeLoadTest {
    @Test fun loadsLibraryAndReportsProtocolVersion() {
        System.loadLibrary("duvo") // must not throw
        assertTrue(MoonlightBridge.protocolVersion() > 0)
    }

    @Test fun stageNamesComeFromMoonlightCommonC() {
        assertTrue(MoonlightBridge.stageName(1).isNotBlank())
    }

    @Test fun newInputEventsExistAndDoNotThrowWithoutSession() {
        System.loadLibrary("duvo")
        MoonlightBridge.sendScroll(120)
        MoonlightBridge.sendHScroll(-120)
        MoonlightBridge.sendText("ação ç ñ 日本 \uD83D\uDE00")
        MoonlightBridge.sendKey(0x41, true, NativeBridge.MOD_CTRL)
        MoonlightBridge.sendKey(0x41, false, NativeBridge.MOD_CTRL)
    }

    @Test fun streamingAndGamepadNativesExistWithoutSession() {
        System.loadLibrary("duvo")
        assertTrue(MoonlightBridge.protocolVersion() >= 3)
        MoonlightBridge.sendMousePosition(10, 10, 100, 100)
        MoonlightBridge.sendTouch(NativeBridge.TOUCH_DOWN, 0, 0.5f, 0.5f)
        MoonlightBridge.sendControllerArrival(0, 1, NativeBridge.CTYPE_XBOX, 0xFFFF, 0)
        MoonlightBridge.sendController(0, 1, 0, 0, 0, 0, 0, 0, 0)
        MoonlightBridge.sendControllerMotion(0, NativeBridge.MOTION_GYRO, 0f, 0f, 0f)
        MoonlightBridge.hostFeatureFlags()
        assertTrue(MoonlightBridge.rttMs() >= -1)
        assertTrue(MoonlightBridge.lastHostLatencyTenthsMs() >= 0)
    }
}
