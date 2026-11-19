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
package app.duvo.remote.input

import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remote-control preferences as a JSON file. Loaded once at construction; every setter saves
 * immediately (temp file renamed over the target). A corrupt file yields defaults and is left
 * untouched until the next setter overwrites it. Unknown/missing panel ids are repaired on load.
 */
class RemotePrefs(private val file: File) {
    private var _order: List<PanelId> = PanelId.values().toList()
    private var _hidden: Set<PanelId> = emptySet()
    private var _pad = PadConfig()
    private var _haptics = true
    private var _tapSound = false
    private var _volume = true
    private var _clip = false

    init { load() }

    var panelOrder: List<PanelId>
        get() = _order
        set(v) { synchronized(this) { _order = v.distinct() + PanelId.values().filter { it !in v }; save() } }
    var hidden: Set<PanelId>
        get() = _hidden
        set(v) { synchronized(this) { _hidden = v.toSet(); save() } }
    var padConfig: PadConfig
        get() = _pad
        set(v) { synchronized(this) { _pad = sanitize(v); save() } }
    var haptics: Boolean
        get() = _haptics
        set(v) { synchronized(this) { _haptics = v; save() } }
    var tapSound: Boolean
        get() = _tapSound
        set(v) { synchronized(this) { _tapSound = v; save() } }
    var volumeButtonsControlPc: Boolean
        get() = _volume
        set(v) { synchronized(this) { _volume = v; save() } }
    var autoClipboardOnResume: Boolean
        get() = _clip
        set(v) { synchronized(this) { _clip = v; save() } }

    /** Replaces non-finite or out-of-range fields by their defaults so no consumer can divide by zero. */
    private fun sanitize(c: PadConfig): PadConfig {
        val d = PadConfig()
        fun pos(v: Float, def: Float) = if (v.isFinite() && v > 0f) v else def
        return PadConfig(
            sensitivity = pos(c.sensitivity, d.sensitivity),
            acceleration = c.acceleration,
            leftHanded = c.leftHanded,
            tapSlopPx = if (c.tapSlopPx.isFinite() && c.tapSlopPx >= 0f) c.tapSlopPx else d.tapSlopPx,
            tapMaxMs = if (c.tapMaxMs > 0) c.tapMaxMs else d.tapMaxMs,
            dragLockMs = if (c.dragLockMs > 0) c.dragLockMs else d.dragLockMs,
            scrollPxPerNotch = pos(c.scrollPxPerNotch, d.scrollPxPerNotch),
            pinchPxPerStep = pos(c.pinchPxPerStep, d.pinchPxPerStep),
            swipePx = pos(c.swipePx, d.swipePx),
        )
    }

    private fun ids(a: JSONArray?): List<PanelId> =
        if (a == null) emptyList()
        else (0 until a.length()).mapNotNull { runCatching { PanelId.valueOf(a.getString(it)) }.getOrNull() }

    @Synchronized private fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            val ord = ids(o.optJSONArray("panelOrder")).distinct()
            _order = ord + PanelId.values().filter { it !in ord }
            _hidden = ids(o.optJSONArray("hidden")).toSet()
            val d = PadConfig()
            o.optJSONObject("pad")?.let { p ->
                _pad = sanitize(PadConfig(
                    sensitivity = p.optDouble("sensitivity", d.sensitivity.toDouble()).toFloat(),
                    acceleration = p.optBoolean("acceleration", d.acceleration),
                    leftHanded = p.optBoolean("leftHanded", d.leftHanded),
                    tapSlopPx = p.optDouble("tapSlopPx", d.tapSlopPx.toDouble()).toFloat(),
                    tapMaxMs = p.optLong("tapMaxMs", d.tapMaxMs),
                    dragLockMs = p.optLong("dragLockMs", d.dragLockMs),
                    scrollPxPerNotch = p.optDouble("scrollPxPerNotch", d.scrollPxPerNotch.toDouble()).toFloat(),
                    pinchPxPerStep = p.optDouble("pinchPxPerStep", d.pinchPxPerStep.toDouble()).toFloat(),
                    swipePx = p.optDouble("swipePx", d.swipePx.toDouble()).toFloat(),
                ))
            }
            _haptics = o.optBoolean("haptics", true)
            _tapSound = o.optBoolean("tapSound", false)
            _volume = o.optBoolean("volumeButtonsControlPc", true)
            _clip = o.optBoolean("autoClipboardOnResume", false)
        } catch (e: Exception) {
            log("prefs unreadable, using defaults: ${e.message}")
            _order = PanelId.values().toList(); _hidden = emptySet(); _pad = PadConfig()
            _haptics = true; _tapSound = false; _volume = true; _clip = false
        }
    }

    @Synchronized private fun save() {
        val p = _pad
        val o = JSONObject()
            .put("panelOrder", JSONArray(_order.map { it.name }))
            .put("hidden", JSONArray(_hidden.map { it.name }))
            .put(
                "pad",
                JSONObject().put("sensitivity", p.sensitivity.toDouble()).put("acceleration", p.acceleration)
                    .put("leftHanded", p.leftHanded).put("tapSlopPx", p.tapSlopPx.toDouble())
                    .put("tapMaxMs", p.tapMaxMs).put("dragLockMs", p.dragLockMs)
                    .put("scrollPxPerNotch", p.scrollPxPerNotch.toDouble())
                    .put("pinchPxPerStep", p.pinchPxPerStep.toDouble()).put("swipePx", p.swipePx.toDouble()),
            )
            .put("haptics", _haptics).put("tapSound", _tapSound)
            .put("volumeButtonsControlPc", _volume).put("autoClipboardOnResume", _clip)
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) {
                // Windows (unit tests) refuses to rename over an existing file; Android's rename replaces atomically.
                file.delete()
                check(tmp.renameTo(file)) { "cannot replace ${file.name}" }
            }
        } catch (e: java.io.IOException) {
            log("prefs not saved: ${e.message}")
        } catch (e: IllegalStateException) {
            log("prefs not saved: ${e.message}")
        }
    }

    private fun log(msg: String) {
        try { Log.w("RemotePrefs", msg) } catch (_: Throwable) { /* plain JVM unit tests */ }
    }
}
