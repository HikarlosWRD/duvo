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

package app.duvo.remote.discovery

import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** A PC this device has paired with; the TLS pin itself lives in CertStore, keyed by [uniqueId]. */
data class PairedHost(
    val uniqueId: String,
    val name: String,
    val addresses: List<String>,
    val port: Int,
    val lastUsedEpochMs: Long,
)

interface PairedHostStore {
    fun load(): List<PairedHost>
    fun upsert(h: PairedHost)
    fun remove(uniqueId: String)
    fun touch(uniqueId: String, nowMs: Long)
}

/** JSON file store. Writes go to a temp file renamed over the target; a corrupt file reads as empty and is left alone. */
class FilePairedHostStore(private val file: File) : PairedHostStore {
    @Synchronized override fun load(): List<PairedHost> = read()

    @Synchronized override fun upsert(h: PairedHost) =
        write(read().filter { it.uniqueId != h.uniqueId } + h)

    @Synchronized override fun remove(uniqueId: String) =
        write(read().filter { it.uniqueId != uniqueId })

    @Synchronized override fun touch(uniqueId: String, nowMs: Long) {
        val cur = read()
        if (cur.none { it.uniqueId == uniqueId }) return
        write(cur.map { if (it.uniqueId == uniqueId) it.copy(lastUsedEpochMs = nowMs) else it })
    }

    private fun read(): List<PairedHost> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONObject(file.readText()).getJSONArray("hosts")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ips = o.getJSONArray("addresses")
                PairedHost(
                    o.getString("uniqueId"), o.getString("name"),
                    (0 until ips.length()).map { ips.getString(it) },
                    o.getInt("port"), o.getLong("lastUsedEpochMs"),
                )
            }
        } catch (e: Exception) {
            log("paired_hosts unreadable, treating as empty: ${e.message}")
            emptyList()
        }
    }

    private fun write(hosts: List<PairedHost>) {
        val arr = JSONArray()
        hosts.forEach {
            arr.put(
                JSONObject().put("uniqueId", it.uniqueId).put("name", it.name)
                    .put("addresses", JSONArray(it.addresses)).put("port", it.port)
                    .put("lastUsedEpochMs", it.lastUsedEpochMs),
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("hosts", arr).toString())
        if (!tmp.renameTo(file)) {
            // Windows (unit tests) refuses to rename over an existing file; Android's rename replaces atomically.
            file.delete()
            check(tmp.renameTo(file)) { "cannot replace ${file.name}" }
        }
    }

    private fun log(msg: String) {
        try { Log.w("PairedHostStore", msg) } catch (_: Throwable) { /* plain JVM unit tests */ }
    }
}
