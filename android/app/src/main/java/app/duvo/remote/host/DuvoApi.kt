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
 */package app.duvo.remote.host

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Snapshot from GET /duvo/status (and /duvo/events). */
data class DuvoStatus(
    val seq: Long,
    val activeApp: String,
    val windowTitle: String,
    val mediaTitle: String,
    val mediaArtist: String,
    val mediaPlaying: Boolean,
    /** 0..100, or null when the PC did not report it. */
    val volume: Int?,
    val muted: Boolean,
)

data class PcWindow(val id: Long, val title: String, val process: String)
data class PcApp(val id: String, val name: String)

enum class PowerAction(val wire: String, val label: String) {
    SHUTDOWN("shutdown", "Desligar"),
    RESTART("restart", "Reiniciar"),
    SLEEP("sleep", "Suspender"),
    LOGOFF("logoff", "Sair da conta"),
    LOCK("lock", "Bloquear"),
}

/** The host refused because this device lacks the permission (HTTP 403). */
class ApiForbidden : IOException(FORBIDDEN_MESSAGE)
class ApiNotFound : IOException("O item não existe mais no PC.")
/** activate 409: elevated window, secure desktop, or the PC refused to switch. */
class ApiDenied : IOException(DENIED_MESSAGE)

const val FORBIDDEN_MESSAGE = "O PC não deu essa permissão a este celular."
const val DENIED_MESSAGE = "Não foi possível trocar para essa janela."
const val MAX_CLIPBOARD_IMAGE_BYTES = 10 * 1024 * 1024
const val IMAGE_TOO_LARGE_MESSAGE = "Imagem grande demais (máximo 10 MB)."

/** Transport seam: (method, path, query, body, contentType, readTimeoutMs) → (status, bytes). */
fun interface DuvoTransport {
    suspend fun call(method: String, path: String, query: List<Pair<String, String>>, body: ByteArray?, contentType: String, readTimeoutMs: Long): Pair<Int, ByteArray>
}

fun HostClient.duvoTransport() = DuvoTransport { m, p, q, b, ct, t -> duvoCall(m, p, q, b, ct, t) }

/** Client for the Duvo host API (M2 §6.6) and Apollo's text clipboard, always over pinned HTTPS. */
class DuvoApi(private val t: DuvoTransport) {
    private suspend fun call(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        contentType: String = "application/json",
        readTimeoutMs: Long = 7000,
    ): Pair<Int, ByteArray> {
        val (code, bytes) = t.call(method, path, query, body, contentType, readTimeoutMs)
        when (code) {
            in 200..299 -> return code to bytes
            403 -> throw ApiForbidden()
            404 -> throw ApiNotFound()
            409 -> throw ApiDenied()
            413 -> throw IOException(IMAGE_TOO_LARGE_MESSAGE)
            else -> throw IOException("O PC respondeu $code")
        }
    }

    private fun json(b: ByteArray) = JSONObject(String(b, Charsets.UTF_8))

    /** The permissions this phone has on the PC (always readable by the phone itself). */
    suspend fun access(): AccessInfo = AccessInfo(json(call("GET", "duvo/access").second).optLong("perm").toInt())

    suspend fun status(): DuvoStatus = parseStatus(json(call("GET", "duvo/status").second))

    /** Long-poll: returns when seq changes or after ~25 s. */
    suspend fun events(since: Long): DuvoStatus =
        parseStatus(json(call("GET", "duvo/events", listOf("since" to since.toString(), "timeout" to "25"), readTimeoutMs = 35_000).second))

    suspend fun windows(): List<PcWindow> {
        val arr = json(call("GET", "duvo/windows").second).optJSONArray("windows") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { PcWindow(it.optLong("id"), it.optString("title"), it.optString("process")) }
    }

    /** PNG icon of a Start Menu app, or null when the PC has none. */
    suspend fun appIcon(id: String): ByteArray? = try {
        val b64 = json(call("GET", "duvo/apps/icon", listOf("id" to id)).second).optString("png")
        if (b64.isEmpty()) null else java.util.Base64.getDecoder().decode(b64)
    } catch (e: ApiNotFound) {
        null
    }

    suspend fun activate(id: Long) { call("POST", "duvo/windows/activate", listOf("id" to id.toULong().toString())) }
    suspend fun showDesktop() { call("POST", "duvo/desktop") }

    suspend fun apps(): List<PcApp> {
        val arr = json(call("GET", "duvo/apps").second).optJSONArray("apps") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { PcApp(it.optString("id"), it.optString("name")) }
    }

    suspend fun launch(id: String) { call("POST", "duvo/apps/launch", listOf("id" to id)) }
    suspend fun power(a: PowerAction) { call("POST", "duvo/power", listOf("action" to a.wire)) }
    suspend fun setVolume(level: Int) { call("POST", "duvo/volume", listOf("level" to level.coerceIn(0, 100).toString())) }
    suspend fun setMute(muted: Boolean) { call("POST", "duvo/volume", listOf("mute" to if (muted) "1" else "0")) }

    suspend fun getText(): String? {
        val (code, b) = call("GET", "actions/clipboard", listOf("type" to "text"))
        return if (code == 204 || b.isEmpty()) null else String(b, Charsets.UTF_8)
    }

    suspend fun setText(s: String) {
        if (s.isEmpty()) return
        call("POST", "actions/clipboard", listOf("type" to "text"), s.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")
    }

    suspend fun getImage(): ByteArray? {
        val (code, b) = call("GET", "duvo/clipboard", listOf("type" to "image"))
        if (code == 204 || b.isEmpty()) return null
        return java.util.Base64.getMimeDecoder().decode(json(b).getString("png"))
    }

    suspend fun setImage(png: ByteArray) {
        if (png.isEmpty()) return
        if (png.size > MAX_CLIPBOARD_IMAGE_BYTES) throw IOException(IMAGE_TOO_LARGE_MESSAGE)
        val b64 = java.util.Base64.getEncoder().encodeToString(png)
        call("POST", "duvo/clipboard", listOf("type" to "image"), JSONObject().put("png", b64).toString().toByteArray())
    }

    companion object {
        fun parseStatus(j: JSONObject): DuvoStatus {
            val media = j.optJSONObject("media") ?: JSONObject()
            val vol = j.optJSONObject("volume") ?: JSONObject()
            return DuvoStatus(
                seq = j.optLong("seq"),
                activeApp = j.optString("activeApp"),
                windowTitle = j.optString("windowTitle"),
                mediaTitle = media.optString("title"),
                mediaArtist = media.optString("artist"),
                mediaPlaying = media.optBoolean("playing"),
                volume = if (vol.isNull("level") || !vol.has("level")) null else vol.optInt("level"),
                muted = vol.optBoolean("muted"),
            )
        }
    }
}
