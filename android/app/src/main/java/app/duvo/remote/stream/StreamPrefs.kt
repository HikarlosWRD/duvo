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

import android.util.Log
import java.io.File
import org.json.JSONObject

/** Which mode a stream belongs to; each keeps its own settings (spec §8.3.2). */
/**
 * REMOTE = mirror the PC screen while controlling it; GAMING = on-screen pad; MONITOR = the device becomes an
 * extended Windows monitor with touch (spec §8.9).
 */
enum class StreamMode { REMOTE, GAMING, MONITOR }

enum class Codec(val label: String, val mime: String?) {
    AUTO("Automático", null),
    H264("H.264", DecoderProbe.MIME_AVC),
    HEVC("HEVC (H.265)", DecoderProbe.MIME_HEVC),
    AV1("AV1", DecoderProbe.MIME_AV1),
}

/** What the host captures: its main monitor, or a virtual display sized for the phone (SudoVDA). */
enum class HostScreen(val label: String) { PRIMARY("Monitor principal do PC"), VIRTUAL("Tela virtual") }

/** Where the PC's sound plays. The host does not stream audio it plays locally, so there is no "both". */
enum class AudioOut(val label: String) { PHONE("No celular"), PC("No PC") }

/** Remote-mode mirroring: taps as mouse clicks, or as Windows touch. */
enum class TouchMode(val label: String) { MOUSE("Mouse (clique onde tocar)"), NATIVE("Toque nativo do Windows") }

data class StreamSettings(
    /** Null = the device screen size (fitted to the decoder); else an explicit width x height. */
    val width: Int? = null,
    val height: Int? = null,
    /** Null = automatic (display refresh, capped by the decoder). */
    val fps: Int? = null,
    /** Null = automatic (Moonlight-like table). */
    val bitrateKbps: Int? = null,
    val codec: Codec = Codec.AUTO,
    val screen: HostScreen = HostScreen.PRIMARY,
    val audio: AudioOut = AudioOut.PC,
    val showStats: Boolean = false,
    val touchMode: TouchMode = TouchMode.MOUSE,
) {
    companion object {
        fun defaultsFor(mode: StreamMode) = when (mode) {
            StreamMode.REMOTE -> StreamSettings(screen = HostScreen.PRIMARY, audio = AudioOut.PC)
            StreamMode.GAMING -> StreamSettings(screen = HostScreen.PRIMARY, audio = AudioOut.PHONE)
            // A second screen for the PC: virtual display (extended, not mirrored), native touch, sound stays on the PC.
            StreamMode.MONITOR -> StreamSettings(screen = HostScreen.VIRTUAL, audio = AudioOut.PC, touchMode = TouchMode.NATIVE)
        }

        /** Resolution choices offered besides "device screen". */
        val RESOLUTIONS = listOf(1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160)
        val FPS_CHOICES = listOf(30, 60, 90, 120)
        const val MIN_BITRATE_KBPS = 1_000
        const val MAX_BITRATE_KBPS = 150_000
    }
}

/** What a session actually asks the host for, after fitting [StreamSettings] to the decoder. */
data class ResolvedStream(
    val mime: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val virtualDisplay: Boolean,
    val hostAudio: Boolean,
    /** pt-BR note when a choice had to be changed (codec missing, size above the decoder limit). */
    val warning: String? = null,
)

object StreamResolver {
    fun resolve(
        s: StreamSettings,
        caps: List<DecoderCaps>,
        screenW: Int,
        screenH: Int,
        displayHz: Float,
    ): ResolvedStream {
        val warnings = mutableListOf<String>()
        var pool = caps
        if (s.codec.mime != null) {
            val only = caps.filter { it.mime == s.codec.mime }
            if (only.isEmpty()) warnings += "Este aparelho não decodifica ${s.codec.label}; usando o automático."
            else pool = only
        }
        val wantW = s.width ?: screenW
        val wantH = s.height ?: screenH
        val rec = DecoderProbe.recommend(pool, wantW, wantH, displayHz)
        if (s.width != null && (rec.width < wantW || rec.height < wantH)) {
            warnings += "Resolução ajustada para ${rec.width}x${rec.height} (limite do decoder)."
        }
        val fps = s.fps ?: rec.fps
        val bitrate = (s.bitrateKbps ?: StreamConfig.defaultBitrateKbps(rec.width, rec.height, fps))
            .coerceIn(StreamSettings.MIN_BITRATE_KBPS, StreamSettings.MAX_BITRATE_KBPS)
        return ResolvedStream(
            mime = rec.mime,
            width = rec.width,
            height = rec.height,
            fps = fps,
            bitrateKbps = bitrate,
            virtualDisplay = s.screen == HostScreen.VIRTUAL,
            hostAudio = s.audio == AudioOut.PC,
            warning = warnings.joinToString(" ").ifEmpty { null },
        )
    }
}

/** Per-mode [StreamSettings] in one JSON file. */
class StreamPrefs(private val file: File) {
    private val map = mutableMapOf<StreamMode, StreamSettings>()

    init { load() }

    @Synchronized fun get(mode: StreamMode): StreamSettings = map[mode] ?: StreamSettings.defaultsFor(mode)

    @Synchronized fun set(mode: StreamMode, s: StreamSettings) {
        map[mode] = sanitize(s)
        save()
    }

    private fun sanitize(s: StreamSettings): StreamSettings {
        val sizeOk = s.width != null && s.height != null && s.width > 0 && s.height > 0
        return s.copy(
            width = if (sizeOk) s.width else null,
            height = if (sizeOk) s.height else null,
            fps = s.fps?.takeIf { it in 10..240 },
            bitrateKbps = s.bitrateKbps?.coerceIn(StreamSettings.MIN_BITRATE_KBPS, StreamSettings.MAX_BITRATE_KBPS),
        )
    }

    private fun toJson(s: StreamSettings) = JSONObject().apply {
        s.width?.let { put("width", it) }
        s.height?.let { put("height", it) }
        s.fps?.let { put("fps", it) }
        s.bitrateKbps?.let { put("bitrateKbps", it) }
        put("codec", s.codec.name)
        put("screen", s.screen.name)
        put("audio", s.audio.name)
        put("showStats", s.showStats)
        put("touchMode", s.touchMode.name)
    }

    private inline fun <reified E : Enum<E>> JSONObject.enumOr(key: String, def: E): E =
        runCatching { enumValueOf<E>(getString(key)) }.getOrDefault(def)

    private fun fromJson(o: JSONObject, def: StreamSettings) = sanitize(
        StreamSettings(
            width = if (o.has("width")) o.optInt("width") else null,
            height = if (o.has("height")) o.optInt("height") else null,
            fps = if (o.has("fps")) o.optInt("fps") else null,
            bitrateKbps = if (o.has("bitrateKbps")) o.optInt("bitrateKbps") else null,
            codec = o.enumOr("codec", def.codec),
            screen = o.enumOr("screen", def.screen),
            audio = o.enumOr("audio", def.audio),
            showStats = o.optBoolean("showStats", def.showStats),
            touchMode = o.enumOr("touchMode", def.touchMode),
        ),
    )

    private fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            for (m in StreamMode.values()) o.optJSONObject(m.name)?.let { map[m] = fromJson(it, StreamSettings.defaultsFor(m)) }
            // v2: Gaming no longer defaults to the virtual (extra) monitor; that belongs to the Monitor mode.
            if (o.optInt("v", 1) < 2) {
                map[StreamMode.GAMING]?.let { if (it.screen == HostScreen.VIRTUAL) map[StreamMode.GAMING] = it.copy(screen = HostScreen.PRIMARY) }
                save()
            }
        } catch (e: Exception) {
            Log.w("DuvoPrefs", "stream prefs unreadable, using defaults", e)
        }
    }

    private fun save() {
        val o = JSONObject()
        o.put("v", 2)
        for ((m, s) in map) o.put(m.name, toJson(s))
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: Exception) {
            Log.w("DuvoPrefs", "stream prefs not saved", e)
        }
    }
}
