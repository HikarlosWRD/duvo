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
package app.duvo.remote.update

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** The newest published release, as read from GitHub. */
data class ReleaseInfo(val version: String, val pageUrl: String, val apkUrl: String?)

/**
 * Looks for a newer Duvo release on GitHub. It only reads the public release list; installing stays with
 * the user (the release page shows the SHA-256 of each file), so nothing is downloaded or run silently.
 */
object UpdateChecker {
    const val LATEST_URL = "https://api.github.com/repos/HikarlosWRD/duvo/releases/latest"

    fun parseLatest(json: String): ReleaseInfo? = try {
        val o = JSONObject(json)
        val tag = o.getString("tag_name").trim().removePrefix("v")
        val assets = o.optJSONArray("assets")
        var apk: String? = null
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url")
        }
        ReleaseInfo(tag, o.getString("html_url"), apk)
    } catch (e: Exception) {
        null
    }

    /** True when [candidate] ("0.2.0", "1.0") is a higher dotted number than [current]. Non-numeric parts count as 0. */
    fun isNewer(current: String, candidate: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(current)
        val b = parts(candidate)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (y != x) return y > x
        }
        return false
    }

    /** Blocking; call off the main thread. Null when offline, rate limited or there is no release yet. */
    fun fetchLatest(): ReleaseInfo? = try {
        val c = URL(LATEST_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode == 200) parseLatest(c.inputStream.bufferedReader().readText()) else null
    } catch (e: Exception) {
        null
    }
}
