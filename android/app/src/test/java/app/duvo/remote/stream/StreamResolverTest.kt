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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StreamResolverTest {
    @get:Rule val tmp = TemporaryFolder()

    private val avc = DecoderCaps(DecoderProbe.MIME_AVC, "c2.avc", true, 1920, 1088, 60.0, false)
    private val hevc = DecoderCaps(DecoderProbe.MIME_HEVC, "c2.hevc", true, 1920, 1088, 60.0, true)

    @Test fun `padroes por modo`() {
        val r = StreamSettings.defaultsFor(StreamMode.REMOTE)
        val g = StreamSettings.defaultsFor(StreamMode.GAMING)
        assertEquals(HostScreen.PRIMARY, r.screen); assertEquals(AudioOut.PC, r.audio)
        assertEquals(HostScreen.PRIMARY, g.screen); assertEquals(AudioOut.PHONE, g.audio)
    }

    @Test fun `modo monitor usa tela virtual, toque nativo e som no PC`() {
        val m = StreamSettings.defaultsFor(StreamMode.MONITOR)
        assertEquals(HostScreen.VIRTUAL, m.screen)
        assertEquals(TouchMode.NATIVE, m.touchMode)
        assertEquals(AudioOut.PC, m.audio)
        val r = StreamResolver.resolve(m, listOf(avc, hevc), 1920, 1200, 60f)
        assertTrue(r.virtualDisplay); assertTrue(r.hostAudio)
    }

    @Test fun `automatico usa a tela do aparelho e o melhor codec`() {
        val r = StreamResolver.resolve(StreamSettings.defaultsFor(StreamMode.GAMING), listOf(avc, hevc), 1920, 1080, 60f)
        assertEquals(DecoderProbe.MIME_HEVC, r.mime)
        assertEquals(1920, r.width); assertEquals(1080, r.height); assertEquals(60, r.fps)
        assertFalse(r.virtualDisplay); assertFalse(r.hostAudio)
        assertEquals(StreamConfig.defaultBitrateKbps(1920, 1080, 60), r.bitrateKbps)
        assertNull(r.warning)
    }

    @Test fun `codec forcado e respeitado, ausente cai no automatico com aviso`() {
        val forced = StreamResolver.resolve(StreamSettings(codec = Codec.H264), listOf(avc, hevc), 1920, 1080, 60f)
        assertEquals(DecoderProbe.MIME_AVC, forced.mime)
        val missing = StreamResolver.resolve(StreamSettings(codec = Codec.AV1), listOf(avc, hevc), 1920, 1080, 60f)
        assertEquals(DecoderProbe.MIME_HEVC, missing.mime)
        assertNotNull(missing.warning)
    }

    @Test fun `resolucao acima do decoder e reduzida com aviso, bitrate manual limitado`() {
        val r = StreamResolver.resolve(StreamSettings(width = 3840, height = 2160, bitrateKbps = 999_999), listOf(hevc), 1920, 1080, 60f)
        assertTrue(r.width <= 1920 && r.height <= 1088)
        assertNotNull(r.warning)
        assertEquals(StreamSettings.MAX_BITRATE_KBPS, r.bitrateKbps)
    }

    @Test fun `prefs salvam por modo e voltam do arquivo`() {
        val f = tmp.newFile("stream.json").also { it.delete() }
        val p = StreamPrefs(f)
        assertEquals(StreamSettings.defaultsFor(StreamMode.GAMING), p.get(StreamMode.GAMING))
        p.set(StreamMode.GAMING, StreamSettings(width = 1280, height = 720, fps = 120, codec = Codec.HEVC, showStats = true))
        p.set(StreamMode.REMOTE, StreamSettings(touchMode = TouchMode.NATIVE, width = 5, height = null, fps = 1000))
        val again = StreamPrefs(f)
        assertEquals(StreamSettings(width = 1280, height = 720, fps = 120, codec = Codec.HEVC, showStats = true), again.get(StreamMode.GAMING))
        val remote = again.get(StreamMode.REMOTE)
        assertEquals(TouchMode.NATIVE, remote.touchMode)
        assertNull(remote.width); assertNull(remote.fps) // invalid values dropped
    }
}
