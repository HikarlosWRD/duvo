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
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderProbeTest {

    private data class FakeCodec(
        override val name: String,
        override val mime: String,
        override val isHardware: Boolean,
        override val maxWidth: Int = 3840,
        override val maxHeight: Int = 2160,
        override val maxFpsAt1080p: Double? = 60.0,
        override val lowLatency: Boolean = false,
        override val widthAlignment: Int = 2,
        override val heightAlignment: Int = 2,
        private val jointMax: Pair<Int, Int>? = null,
        private val supportedFps: Double? = null,
        private val achievableFps: Double? = null,
    ) : CodecInfoView {
        override fun isSizeSupported(width: Int, height: Int) =
            jointMax == null || (width <= jointMax.first && height <= jointMax.second)

        override fun supportedFrameRateUpper(width: Int, height: Int) = supportedFps ?: maxFpsAt1080p

        override fun achievableFrameRateUpper(width: Int, height: Int) = achievableFps
    }

    private fun caps(
        mime: String,
        maxWidth: Int = 3840,
        maxHeight: Int = 2160,
        maxFpsAt1080p: Double? = 60.0,
        lowLatency: Boolean = false,
        hardware: Boolean = true,
    ) = DecoderCaps(mime, "dec.$mime", hardware, maxWidth, maxHeight, maxFpsAt1080p, lowLatency)

    @Test
    fun `ignora decoders de software quando existe hardware`() {
        val result = DecoderProbe.probe(
            listOf(
                FakeCodec("sw.avc", "video/avc", isHardware = false),
                FakeCodec("hw.avc", "video/avc", isHardware = true),
                FakeCodec("sw.hevc", "video/hevc", isHardware = false),
            ),
        )

        val avc = result.filter { it.mime == "video/avc" }
        assertEquals(1, avc.size)
        assertEquals("hw.avc", avc.single().codecName)
        assertTrue(avc.single().hardware)
        // Sem hardware para o mime, o de software continua listado.
        val hevc = result.filter { it.mime == "video/hevc" }
        assertEquals(1, hevc.size)
        assertFalse(hevc.single().hardware)
    }

    @Test
    fun `recommend prefere HEVC hardware com lowLatency`() {
        val defaults = DecoderProbe.recommend(
            listOf(
                caps("video/avc", lowLatency = true),
                caps("video/hevc", lowLatency = true),
            ),
            screenW = 1920,
            screenH = 1080,
            displayHz = 120f,
        )

        assertEquals("video/hevc", defaults.mime)
    }

    @Test
    fun `recommend nunca passa do maximo do decoder`() {
        val defaults = DecoderProbe.recommend(
            listOf(caps("video/hevc", maxWidth = 1920, maxHeight = 1080)),
            screenW = 2000,
            screenH = 1200,
            displayHz = 120f,
        )

        assertTrue(defaults.width <= 1920 && defaults.height <= 1080)
        assertEquals(0, defaults.width % 2)
        assertEquals(0, defaults.height % 2)
        // Mantem a proporcao da tela (2000x1200 = 5:3), com tolerancia do arredondamento.
        val screenRatio = 2000.0 / 1200.0
        assertEquals(screenRatio, defaults.width.toDouble() / defaults.height, 0.02)
    }

    @Test
    fun `recommend usa 60 fps se 1080p120 nao suportado`() {
        val defaults = DecoderProbe.recommend(
            listOf(caps("video/hevc", maxFpsAt1080p = 60.0)),
            screenW = 1920,
            screenH = 1080,
            displayHz = 120f,
        )

        assertEquals(60, defaults.fps)
    }

    @Test
    fun `recommend usa 120 fps quando 1080p120 suportado`() {
        val defaults = DecoderProbe.recommend(
            listOf(caps("video/hevc", maxFpsAt1080p = 120.0)),
            screenW = 1920,
            screenH = 1080,
            displayHz = 120f,
        )

        assertEquals(120, defaults.fps)
    }

    @Test
    fun `recommend usa 60 fps quando fps maximo desconhecido`() {
        val defaults = DecoderProbe.recommend(
            listOf(caps("video/avc", maxFpsAt1080p = null)),
            screenW = 1920,
            screenH = 1080,
            displayHz = 120f,
        )

        assertEquals(60, defaults.fps)
    }

    @Test
    fun `recommend prefere HEVC hardware a H264 hardware`() {
        val defaults = DecoderProbe.recommend(
            listOf(caps("video/avc", lowLatency = true), caps("video/hevc", lowLatency = false)),
            screenW = 1920,
            screenH = 1080,
            displayHz = 120f,
        )

        assertEquals("video/hevc", defaults.mime)
    }

    @Test
    fun `recommend prefere H264 hardware a software`() {
        val defaults = DecoderProbe.recommend(
            listOf(caps("video/hevc", hardware = false), caps("video/avc")),
            screenW = 1920,
            screenH = 1080,
            displayHz = 120f,
        )

        assertEquals("video/avc", defaults.mime)
    }

    @Test
    fun `recommend sem hardware cai para qualquer decoder`() {
        val defaults = DecoderProbe.recommend(
            listOf(caps("video/hevc", hardware = false)),
            screenW = 1280,
            screenH = 720,
            displayHz = 120f,
        )

        assertEquals("video/hevc", defaults.mime)
        assertEquals(1280, defaults.width)
        assertEquals(720, defaults.height)
    }

    @Test
    fun `recommend valida o tamanho com isSizeSupported e desce ate ser suportado`() {
        // Como o Unisoc: larguras ate 1920 e alturas ate 3840 isoladamente, mas juntos so 1920x1088.
        val caps = DecoderProbe.probe(
            listOf(
                FakeCodec(
                    "hw.hevc", "video/hevc", isHardware = true,
                    maxWidth = 1920, maxHeight = 3840, jointMax = 1920 to 1088,
                ),
            ),
        )

        val defaults = DecoderProbe.recommend(caps, screenW = 2000, screenH = 1200, displayHz = 60f)

        assertTrue(caps.single().sizeSupported(defaults.width, defaults.height))
        assertTrue(defaults.width <= 1920 && defaults.height <= 1088)
        assertEquals(0, defaults.width % 2)
        assertEquals(0, defaults.height % 2)
        assertEquals(2000.0 / 1200.0, defaults.width.toDouble() / defaults.height, 0.02)
    }

    @Test
    fun `recommend respeita o alinhamento do decoder`() {
        val caps = DecoderProbe.probe(
            listOf(
                FakeCodec(
                    "hw.hevc", "video/hevc", isHardware = true,
                    widthAlignment = 16, heightAlignment = 16, jointMax = 1920 to 1088,
                ),
            ),
        )

        val defaults = DecoderProbe.recommend(caps, screenW = 2000, screenH = 1200, displayHz = 60f)

        assertEquals(0, defaults.width % 16)
        assertEquals(0, defaults.height % 16)
        assertTrue(defaults.width <= 1920 && defaults.height <= 1088)
    }

    @Test
    fun `recommend limita o fps a taxa de atualizacao da tela`() {
        val caps = DecoderProbe.probe(
            listOf(FakeCodec("hw.hevc", "video/hevc", isHardware = true, maxFpsAt1080p = 212.0)),
        )

        val defaults = DecoderProbe.recommend(caps, screenW = 1920, screenH = 1080, displayHz = 57.99f)

        assertEquals(60, defaults.fps)
    }

    @Test
    fun `recommend prefere taxas alcancaveis as suportadas`() {
        val caps = DecoderProbe.probe(
            listOf(
                FakeCodec(
                    "hw.hevc", "video/hevc", isHardware = true,
                    maxFpsAt1080p = 212.0, supportedFps = 212.0, achievableFps = 60.0,
                ),
            ),
        )

        val defaults = DecoderProbe.recommend(caps, screenW = 1920, screenH = 1080, displayHz = 120f)

        assertEquals(60, defaults.fps)
    }

    // R12(b): a taxa da tela e a maior entre os modos suportados na resolucao atual, nao a do modo atual.
    @Test
    fun `recommend usa a maior taxa entre os modos da resolucao atual`() {
        val current = DisplayModeInfo(id = 1, width = 1220, height = 2712, refreshHz = 60f)
        val modes = listOf(
            current,
            DisplayModeInfo(id = 2, width = 1220, height = 2712, refreshHz = 90f),
            DisplayModeInfo(id = 3, width = 1080, height = 2400, refreshHz = 120f), // outra resolucao
        )

        val defaults = DecoderProbe.recommend(
            listOf(caps("video/hevc", maxFpsAt1080p = 240.0)),
            screenW = 2712,
            screenH = 1220,
            current = current,
            supported = modes,
        )

        assertEquals(90, defaults.fps)
        assertEquals(90f, DisplayModes.maxRefreshHz(current, modes), 0.01f)
    }

    @Test
    fun `modo da tela escolhido e o que casa com o fps do stream`() {
        val m60 = DisplayModeInfo(1, 1220, 2712, 60f)
        val m90 = DisplayModeInfo(2, 1220, 2712, 90f)
        val m120 = DisplayModeInfo(3, 1220, 2712, 120.00001f)
        val other = DisplayModeInfo(4, 1080, 2400, 60f)
        val modes = listOf(m60, m90, m120, other)

        assertEquals(m60, DisplayModes.modeForFps(m120, modes, 60))
        assertEquals(m120, DisplayModes.modeForFps(m60, modes, 120))
        assertEquals(m90, DisplayModes.modeForFps(m60, modes, 90))
        // 58 Hz como no TL10: 60 fps casa com o modo de 57.99 Hz
        val tl10 = DisplayModeInfo(9, 1200, 2000, 57.99f)
        assertEquals(tl10, DisplayModes.modeForFps(tl10, listOf(tl10), 60))
    }

    // R12(c): hardware ".low_latency" (Qualcomm) > FEATURE_LowLatency > outro hardware.
    @Test
    fun `pickDecoder prefere low_latency no nome depois FEATURE_LowLatency depois hardware`() {
        val plain = DecoderCaps("video/hevc", "c2.qti.hevc.decoder", true, 8192, 4320, 120.0, lowLatency = false)
        val named = DecoderCaps("video/hevc", "c2.qti.hevc.decoder.low_latency", true, 8192, 4320, 120.0, lowLatency = false)
        val feature = DecoderCaps("video/hevc", "c2.vendor.hevc.decoder", true, 8192, 4320, 120.0, lowLatency = true)
        val software = DecoderCaps("video/hevc", "c2.android.hevc.decoder", false, 4096, 2304, 60.0, lowLatency = true)
        val avc = DecoderCaps("video/avc", "c2.qti.avc.decoder.low_latency", true, 8192, 4320, 120.0, lowLatency = false)

        assertEquals(named, DecoderProbe.pickDecoder(listOf(software, plain, feature, named, avc), "video/hevc"))
        assertEquals(feature, DecoderProbe.pickDecoder(listOf(software, plain, feature, avc), "video/hevc"))
        assertEquals(plain, DecoderProbe.pickDecoder(listOf(software, plain, avc), "video/hevc"))
        assertEquals(software, DecoderProbe.pickDecoder(listOf(software, avc), "video/hevc"))
        assertEquals(null, DecoderProbe.pickDecoder(listOf(avc), "video/av01"))
    }

    @Test
    fun `chaves de baixa latencia incluem a do fabricante Qualcomm e caem em tentativas`() {
        val qti0 = DecoderTuning.formatKeys("c2.qti.hevc.decoder.low_latency", sdkInt = 35, attempt = 0)
        assertEquals(1, qti0["low-latency"])
        assertEquals(0, qti0["priority"])
        assertEquals(Short.MAX_VALUE.toInt(), qti0["operating-rate"])
        assertEquals(1, qti0["vendor.qti-ext-dec-low-latency.enable"])

        val unisoc = DecoderTuning.formatKeys("c2.unisoc.hevc.decoder", sdkInt = 33, attempt = 0)
        assertFalse(unisoc.keys.any { it.startsWith("vendor.") })
        assertEquals(1, unisoc["low-latency"])

        val omx = DecoderTuning.formatKeys("OMX.qcom.video.decoder.hevc", sdkInt = 29, attempt = 0)
        assertFalse("low-latency" in omx) // KEY_LOW_LATENCY e API 30+
        assertEquals(1, omx["vendor.qti-ext-dec-low-latency.enable"])

        // Se o configure falhar, as tentativas seguintes tiram as chaves mais arriscadas.
        val retry = DecoderTuning.formatKeys("c2.qti.hevc.decoder", sdkInt = 35, attempt = 1)
        assertFalse("priority" in retry || "operating-rate" in retry)
        val last = DecoderTuning.formatKeys("c2.qti.hevc.decoder", sdkInt = 35, attempt = DecoderTuning.ATTEMPTS - 1)
        assertEquals(mapOf("low-latency" to 1), last)
    }
}
