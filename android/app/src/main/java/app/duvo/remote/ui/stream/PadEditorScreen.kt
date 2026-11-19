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
package app.duvo.remote.ui.stream

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.duvo.remote.DuvoApp
import app.duvo.remote.gamepad.LayoutStore
import app.duvo.remote.gamepad.PadLayout
import kotlin.math.hypot
import org.json.JSONObject

/**
 * Layout editor: drag controls, pick one to change its size/opacity, load a preset or a saved layout,
 * save under a name (it becomes the layout in use), delete, export/import as a .json file.
 */
@Composable
fun PadEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = (context.applicationContext as DuvoApp).layoutStore
    var layout by remember { mutableStateOf(store.active()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        val old = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (old != null) activity.requestedOrientation = old }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(layout.toJson().toString(2).toByteArray()) }
        }.onSuccess { toast(context, "Layout exportado.") }.onFailure { toast(context, "Não foi possível exportar.") }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(MAX_IMPORT).toByteArray().decodeToString() }
            PadLayout.fromJson(JSONObject(text ?: ""))
        }.onSuccess { layout = it; selected = null; toast(context, "Layout \"${it.name}\" carregado. Salve para usar.") }
            .onFailure { toast(context, it.message?.takeIf { m -> m.startsWith("Arquivo") } ?: "Arquivo de layout inválido.") }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(layout.controls.size, w, h) {
                        detectTapGestures { p ->
                            selected = layout.controls.lastOrNull { c -> hypot(p.x - c.x * w, p.y - c.y * h) <= c.size * h / 2 }?.id
                        }
                    }
                    .pointerInput(w, h) {
                        var dragging: String? = null
                        detectDragGestures(
                            onDragStart = { p ->
                                dragging = layout.controls.lastOrNull { c -> hypot(p.x - c.x * w, p.y - c.y * h) <= c.size * h / 2 }?.id
                                if (dragging != null) selected = dragging
                            },
                            onDragEnd = { dragging = null },
                            onDrag = { change, delta ->
                                val id = dragging ?: return@detectDragGestures
                                change.consume()
                                layout = layout.copy(controls = layout.controls.map {
                                    if (it.id != id) it else it.copy(x = (it.x + delta.x / w).coerceIn(0f, 1f), y = (it.y + delta.y / h).coerceIn(0f, 1f))
                                })
                            },
                        )
                    },
            ) {
                layout.controls.forEach { c -> PadControlView(c, w, h, pressed = false, selected = c.id == selected) }
            }
        }

        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Box {
                    TextButton(onClick = { menu = true }) { Text("Carregar", color = MaterialTheme.colorScheme.onSurface) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        (LayoutStore.presets() + store.list()).forEach { l ->
                            DropdownMenuItem(text = { Text(l.name) }, onClick = { layout = l; selected = null; menu = false; store.setCurrent(l.name) })
                        }
                    }
                }
                TextButton(onClick = { saving = true }) { Text("Salvar", color = MaterialTheme.colorScheme.onSurface) }
                TextButton(onClick = {
                    store.delete(layout.name)
                    toast(context, "Layout \"${layout.name}\" apagado (se era salvo).")
                    layout = store.active()
                }) { Text("Apagar", color = MaterialTheme.colorScheme.onSurface) }
                TextButton(onClick = { export.launch("${layout.name}.json") }) { Text("Exportar", color = MaterialTheme.colorScheme.onSurface) }
                TextButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Importar", color = MaterialTheme.colorScheme.onSurface) }
                TextButton(onClick = onBack) { Text("Fechar", color = MaterialTheme.colorScheme.onSurface) }
            }
            val c = layout.controls.firstOrNull { it.id == selected }
            if (c == null) {
                Text("Arraste um botão para mover. Toque nele para mudar tamanho e transparência.", color = MaterialTheme.colorScheme.onSurface)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tamanho", color = MaterialTheme.colorScheme.onSurface)
                    Slider(value = c.size, onValueChange = { v -> layout = layout.replace(c.copy(size = v)) }, valueRange = 0.05f..0.6f, modifier = Modifier.width(200.dp))
                    Text("Opacidade", color = MaterialTheme.colorScheme.onSurface)
                    Slider(value = c.opacity, onValueChange = { v -> layout = layout.replace(c.copy(opacity = v)) }, valueRange = 0.05f..1f, modifier = Modifier.width(200.dp))
                }
            }
        }
    }

    if (saving) {
        var name by remember { mutableStateOf(if (LayoutStore.presets().any { it.name == layout.name }) "" else layout.name) }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Salvar layout") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Nome") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val l = layout.copy(name = name.trim())
                    store.save(l)
                    store.setCurrent(l.name)
                    layout = l
                    saving = false
                    toast(context, "Layout salvo e em uso.")
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancelar") } },
        )
    }
}

private fun PadLayout.replace(c: app.duvo.remote.gamepad.PadControl) = copy(controls = controls.map { if (it.id == c.id) c else it })

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val MAX_IMPORT = 256 * 1024
