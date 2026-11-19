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

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/**
 * Thin view over one (video decoder, mime type) pair of [MediaCodecInfo], so that
 * [DecoderProbe] can be unit-tested on the JVM without a device.
 */
interface CodecInfoView {
    val name: String
    val mime: String
    val isHardware: Boolean
    val maxWidth: Int
    val maxHeight: Int

    /** Upper bound of the supported frame rate at 1920x1080, or null if 1080p is unsupported. */
    val maxFpsAt1080p: Double?
    val lowLatency: Boolean

    val widthAlignment: Int get() = 2
    val heightAlignment: Int get() = 2

    /** Whether this exact size is supported (joint check, unlike the independent max ranges). */
    fun isSizeSupported(width: Int, height: Int): Boolean = true

    /** Upper bound of the supported frame rates for this size, or null if unknown. */
    fun supportedFrameRateUpper(width: Int, height: Int): Double? = maxFpsAt1080p

    /** Upper bound of the achievable frame rates for this size, or null if not reported. */
    fun achievableFrameRateUpper(width: Int, height: Int): Double? = null
}

/** Real adapter over [MediaCodecInfo] / [MediaCodecInfo.VideoCapabilities]. */
class MediaCodecInfoView(
    private val info: MediaCodecInfo,
    override val mime: String,
) : CodecInfoView {
    private val caps = info.getCapabilitiesForType(mime)
    private val video = requireNotNull(caps.videoCapabilities) { "not a video codec: $mime" }

    override val name: String get() = info.name
    override val isHardware: Boolean get() = info.isHardwareAccelerated
    override val maxWidth: Int get() = video.supportedWidths.upper
    override val maxHeight: Int get() = video.supportedHeights.upper

    override val maxFpsAt1080p: Double?
        get() = if (video.isSizeSupported(1920, 1080)) {
            achievableFrameRateUpper(1920, 1080) ?: supportedFrameRateUpper(1920, 1080)
        } else {
            null
        }

    override val widthAlignment: Int get() = video.widthAlignment
    override val heightAlignment: Int get() = video.heightAlignment

    override fun isSizeSupported(width: Int, height: Int): Boolean =
        video.isSizeSupported(width, height)

    override fun supportedFrameRateUpper(width: Int, height: Int): Double? =
        if (video.isSizeSupported(width, height)) {
            video.getSupportedFrameRatesFor(width, height).upper
        } else {
            null
        }

    override fun achievableFrameRateUpper(width: Int, height: Int): Double? =
        if (video.isSizeSupported(width, height)) {
            video.getAchievableFrameRatesFor(width, height)?.upper
        } else {
            null
        }

    override val lowLatency: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)

    companion object {
        /** Lists every video decoder (one entry per supported video mime type) on this device. */
        fun listDecoders(): List<CodecInfoView> =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder }
                .flatMap { info ->
                    info.supportedTypes
                        .filter { it.startsWith("video/") }
                        .mapNotNull { mime ->
                            try {
                                MediaCodecInfoView(info, mime).also { it.maxFpsAt1080p }
                            } catch (_: IllegalArgumentException) {
                                null
                            }
                        }
                }
    }
}
