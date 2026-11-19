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

data class DecoderCaps(
    val mime: String,
    val codecName: String,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFpsAt1080p: Double?,
    val lowLatency: Boolean,
    val widthAlignment: Int = 2,
    val heightAlignment: Int = 2,
    /** Joint size check; the max ranges above are independent and may not be combinable. */
    val sizeSupported: (Int, Int) -> Boolean = { _, _ -> true },
    /** Best known fps for a given size (achievable if reported, else supported). */
    val fpsAt: (Int, Int) -> Double? = { _, _ -> maxFpsAt1080p },
)

data class StreamDefaults(val mime: String, val width: Int, val height: Int, val fps: Int)

object DecoderProbe {
    const val MIME_HEVC = "video/hevc"
    const val MIME_AVC = "video/avc"
    const val MIME_AV1 = "video/av01"

    private const val DEFAULT_FPS = 60
    private const val HIGH_FPS = 120

    /**
     * Converts the codec list into capabilities. When a mime type has at least one hardware
     * decoder, its software decoders are dropped.
     */
    fun probe(list: List<CodecInfoView>): List<DecoderCaps> =
        list.groupBy { it.mime }.values.flatMap { sameMime ->
            val hardware = sameMime.filter { it.isHardware }
            (hardware.ifEmpty { sameMime }).map {
                DecoderCaps(
                    mime = it.mime,
                    codecName = it.name,
                    hardware = it.isHardware,
                    maxWidth = it.maxWidth,
                    maxHeight = it.maxHeight,
                    maxFpsAt1080p = it.maxFpsAt1080p,
                    lowLatency = it.lowLatency,
                    widthAlignment = it.widthAlignment,
                    heightAlignment = it.heightAlignment,
                    sizeSupported = it::isSizeSupported,
                    fpsAt = { w, h ->
                        it.achievableFrameRateUpper(w, h) ?: it.supportedFrameRateUpper(w, h)
                    },
                )
            }
        }

    /**
     * Preference: HEVC hw+lowLatency > HEVC hw > H.264 hw > any hw > anything.
     * Size: screen size clamped to the decoder maximum keeping the aspect ratio, aligned to the
     * decoder alignment (and even), then stepped down until the decoder supports that exact size.
     * Fps: min(decoder fps for that size, display refresh rate), quantized down to 120/90/60/30.
     */
    fun recommend(caps: List<DecoderCaps>, screenW: Int, screenH: Int, displayHz: Float): StreamDefaults {
        val display = snapRefresh(displayHz)
        val mime = caps.minByOrNull(::rank)?.mime
            ?: return StreamDefaults(MIME_AVC, evenDown(screenW), evenDown(screenH), quantize(display.toDouble()))
        val best = pickDecoder(caps, mime)!!

        val scale = minOf(
            1.0,
            best.maxWidth.toDouble() / screenW,
            best.maxHeight.toDouble() / screenH,
        )
        val alignW = lcm(2, best.widthAlignment.coerceAtLeast(1))
        val alignH = lcm(2, best.heightAlignment.coerceAtLeast(1))
        val startW = alignDown((screenW * scale).toInt(), alignW)

        var w = startW
        var size: Pair<Int, Int>? = null
        while (w >= alignW) {
            val h = alignDown((w.toLong() * screenH / screenW).toInt(), alignH)
            if (h >= alignH && best.sizeSupported(w, h)) {
                size = w to h
                break
            }
            w -= alignW
        }
        val (width, height) = size
            ?: (startW to alignDown((startW.toLong() * screenH / screenW).toInt(), alignH))

        val decoderFps = best.fpsAt(width, height) ?: DEFAULT_FPS.toDouble()
        return StreamDefaults(best.mime, width, height, quantize(minOf(decoderFps, display.toDouble())))
    }

    /**
     * Like [recommend], with the display refresh rate taken as the highest among the [supported]
     * modes at the [current] resolution (R12b): the stream can switch the panel to that mode.
     */
    fun recommend(
        caps: List<DecoderCaps>,
        screenW: Int,
        screenH: Int,
        current: DisplayModeInfo,
        supported: List<DisplayModeInfo>,
    ): StreamDefaults = recommend(caps, screenW, screenH, DisplayModes.maxRefreshHz(current, supported))

    /**
     * The decoder to use for [mime] (R12c): a hardware decoder whose name has ".low_latency"
     * (Qualcomm's dedicated low-latency instances), then hardware with FEATURE_LowLatency, then any
     * other hardware, then software.
     */
    fun pickDecoder(caps: List<DecoderCaps>, mime: String): DecoderCaps? =
        caps.filter { it.mime == mime }.minByOrNull {
            when {
                it.hardware && it.codecName.contains(".low_latency", ignoreCase = true) -> 0
                it.hardware && it.lowLatency -> 1
                it.hardware -> 2
                else -> 3
            }
        }

    /** Snaps near-standard refresh rates (e.g. 57.99 -> 60), otherwise rounds to nearest. */
    private fun snapRefresh(hz: Float): Int {
        for (t in intArrayOf(60, 90, 120)) {
            if (hz >= t - 3 && hz <= t + 1) return t
        }
        return Math.round(hz).coerceAtLeast(1)
    }

    private fun quantize(fps: Double): Int = when {
        fps >= HIGH_FPS -> HIGH_FPS
        fps >= 90 -> 90
        fps >= DEFAULT_FPS -> DEFAULT_FPS
        else -> 30
    }

    private fun alignDown(v: Int, a: Int): Int = v - v % a

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    private fun lcm(a: Int, b: Int): Int = a / gcd(a, b) * b

    private fun rank(c: DecoderCaps): Int = when {
        c.hardware && c.mime == MIME_HEVC && c.lowLatency -> 0
        c.hardware && c.mime == MIME_HEVC -> 1
        c.hardware && c.mime == MIME_AVC -> 2
        c.hardware -> 3
        else -> 4
    }

    private fun evenDown(v: Int): Int = v and 1.inv()
}
