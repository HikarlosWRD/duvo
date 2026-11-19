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
package app.duvo.remote.gamepad

import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class ControlKind { BUTTON, DPAD, STICK_LEFT, STICK_RIGHT, TRIGGER_LEFT, TRIGGER_RIGHT }

/**
 * One on-screen control. [x]/[y] = center as a fraction of the screen width/height (landscape);
 * [size] = diameter as a fraction of the screen height, so a layout fits phones and tablets alike.
 */
data class PadControl(
    val id: String,
    val kind: ControlKind,
    val label: String,
    val x: Float,
    val y: Float,
    val size: Float,
    val opacity: Float = 0.6f,
    /** Btn flag sent by a BUTTON (also the stick click for nothing else). */
    val button: Int = 0,
)

data class PadLayout(val name: String, val style: PadStyle, val controls: List<PadControl>) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("name", name)
        put("style", style.name)
        put("controls", JSONArray().apply {
            controls.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id); put("kind", c.kind.name); put("label", c.label)
                    put("x", c.x.toDouble()); put("y", c.y.toDouble()); put("size", c.size.toDouble())
                    put("opacity", c.opacity.toDouble()); put("button", c.button)
                })
            }
        })
    }

    /** Same layout with values forced into range (imported files are untrusted). */
    fun sanitized(): PadLayout = copy(
        name = name.trim().take(40).ifEmpty { "Layout" },
        controls = controls.take(64).map {
            it.copy(
                x = it.x.finiteOr(0.5f).coerceIn(0f, 1f),
                y = it.y.finiteOr(0.5f).coerceIn(0f, 1f),
                size = it.size.finiteOr(0.15f).coerceIn(0.05f, 0.6f),
                opacity = it.opacity.finiteOr(0.6f).coerceIn(0.05f, 1f),
                label = it.label.take(8),
                button = it.button and Btn.STANDARD,
            )
        },
    )

    companion object {
        /** Parses an exported layout; throws IllegalArgumentException with a pt-BR message when invalid. */
        fun fromJson(o: JSONObject): PadLayout {
            val style = runCatching { PadStyle.valueOf(o.getString("style")) }.getOrNull()
                ?: throw IllegalArgumentException("Arquivo de layout inválido.")
            val arr = o.optJSONArray("controls") ?: throw IllegalArgumentException("Arquivo de layout inválido.")
            val controls = (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val kind = runCatching { ControlKind.valueOf(c.getString("kind")) }.getOrNull() ?: return@mapNotNull null
                PadControl(
                    id = c.optString("id", "c$i"), kind = kind, label = c.optString("label"),
                    x = c.optDouble("x", 0.5).toFloat(), y = c.optDouble("y", 0.5).toFloat(),
                    size = c.optDouble("size", 0.15).toFloat(), opacity = c.optDouble("opacity", 0.6).toFloat(),
                    button = c.optInt("button"),
                )
            }
            return PadLayout(o.optString("name", "Layout"), style, controls).sanitized()
        }

        /**
         * Factory layouts. Face buttons sit in the Xbox positions (bottom = A); the Switch style keeps
         * Nintendo labels in Nintendo positions (right = "A" sends Xbox B), or, with [swapAB], makes the
         * label match the action (right "A" sends A).
         */
        fun preset(style: PadStyle, swapAB: Boolean = false): PadLayout {
            val face = when (style) {
                PadStyle.XBOX -> listOf("A" to Btn.A, "B" to Btn.B, "X" to Btn.X, "Y" to Btn.Y)
                PadStyle.PLAYSTATION -> listOf("✕" to Btn.A, "○" to Btn.B, "□" to Btn.X, "△" to Btn.Y)
                PadStyle.SWITCH -> if (swapAB) listOf("B" to Btn.B, "A" to Btn.A, "Y" to Btn.Y, "X" to Btn.X)
                else listOf("B" to Btn.A, "A" to Btn.B, "Y" to Btn.X, "X" to Btn.Y)
            }
            val sh = when (style) {
                PadStyle.XBOX -> listOf("LB", "RB", "LT", "RT", "View", "Menu", "Guia")
                PadStyle.PLAYSTATION -> listOf("L1", "R1", "L2", "R2", "Share", "Options", "PS")
                PadStyle.SWITCH -> listOf("L", "R", "ZL", "ZR", "−", "+", "Home")
            }
            // bottom, right, left, top
            val fx = 0.86f; val fy = 0.62f; val d = 0.075f
            val c = listOf(
                PadControl("ls", ControlKind.STICK_LEFT, "", 0.14f, 0.62f, 0.34f),
                PadControl("rs", ControlKind.STICK_RIGHT, "", 0.66f, 0.78f, 0.28f),
                PadControl("dpad", ControlKind.DPAD, "", 0.33f, 0.84f, 0.26f),
                PadControl("a", ControlKind.BUTTON, face[0].first, fx, fy + 0.13f, 0.15f, button = face[0].second),
                PadControl("b", ControlKind.BUTTON, face[1].first, fx + d, fy, 0.15f, button = face[1].second),
                PadControl("x", ControlKind.BUTTON, face[2].first, fx - d, fy, 0.15f, button = face[2].second),
                PadControl("y", ControlKind.BUTTON, face[3].first, fx, fy - 0.13f, 0.15f, button = face[3].second),
                PadControl("lb", ControlKind.BUTTON, sh[0], 0.10f, 0.22f, 0.13f, button = Btn.LB),
                PadControl("rb", ControlKind.BUTTON, sh[1], 0.90f, 0.22f, 0.13f, button = Btn.RB),
                PadControl("lt", ControlKind.TRIGGER_LEFT, sh[2], 0.10f, 0.07f, 0.12f),
                PadControl("rt", ControlKind.TRIGGER_RIGHT, sh[3], 0.90f, 0.07f, 0.12f),
                PadControl("back", ControlKind.BUTTON, sh[4], 0.42f, 0.20f, 0.10f, button = Btn.BACK),
                PadControl("start", ControlKind.BUTTON, sh[5], 0.58f, 0.20f, 0.10f, button = Btn.START),
                PadControl("guide", ControlKind.BUTTON, sh[6], 0.50f, 0.33f, 0.10f, button = Btn.GUIDE),
                PadControl("l3", ControlKind.BUTTON, "L3", 0.04f, 0.92f, 0.09f, button = Btn.LS),
                PadControl("r3", ControlKind.BUTTON, "R3", 0.96f, 0.92f, 0.09f, button = Btn.RS),
            )
            val name = style.label + if (style == PadStyle.SWITCH && swapAB) " (A/B trocados)" else ""
            return PadLayout(name, style, c)
        }
    }
}

private fun Float.finiteOr(d: Float) = if (isFinite()) this else d

/** Saved layouts (one JSON file each) and which one is in use. */
class LayoutStore(private val dir: File) {
    private val current = File(dir, "current.txt")

    @Synchronized fun list(): List<PadLayout> {
        val saved = dir.listFiles { f -> f.name.endsWith(EXT) }.orEmpty().sortedBy { it.name }
            .mapNotNull { f -> runCatching { PadLayout.fromJson(JSONObject(f.readText())) }.getOrNull() }
        return saved
    }

    @Synchronized fun save(layout: PadLayout) {
        dir.mkdirs()
        val l = layout.sanitized()
        File(dir, fileName(l.name)).writeText(l.toJson().toString())
    }

    @Synchronized fun delete(name: String) {
        File(dir, fileName(name)).delete()
        if (currentName() == name) current.delete()
    }

    @Synchronized fun currentName(): String? = runCatching { current.readText().trim() }.getOrNull()?.ifEmpty { null }

    @Synchronized fun setCurrent(name: String) {
        dir.mkdirs()
        runCatching { current.writeText(name) }.onFailure { Log.w("DuvoPad", "current layout not saved", it) }
    }

    /** The layout in use: saved one by name, else a preset by its name, else the Xbox preset. */
    fun active(): PadLayout {
        val name = currentName()
        return list().firstOrNull { it.name == name }
            ?: presets().firstOrNull { it.name == name }
            ?: PadLayout.preset(PadStyle.XBOX)
    }

    private fun fileName(name: String) = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(40) + EXT

    companion object {
        private const val EXT = ".pad.json"
        fun presets() = listOf(
            PadLayout.preset(PadStyle.XBOX),
            PadLayout.preset(PadStyle.PLAYSTATION),
            PadLayout.preset(PadStyle.SWITCH),
            PadLayout.preset(PadStyle.SWITCH, swapAB = true),
        )
    }
}
