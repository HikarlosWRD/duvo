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

package app.duvo.remote.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.duvo.remote.stream.DecoderCaps
import app.duvo.remote.stream.DecoderProbe
import app.duvo.remote.stream.DisplayModeInfo
import app.duvo.remote.stream.DisplayModes
import app.duvo.remote.stream.MediaCodecInfoView
import app.duvo.remote.stream.StreamDefaults

@Composable
fun ProbeScreen() {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    // Streaming is landscape: the wider side is the width.
    val screenW = maxOf(metrics.widthPixels, metrics.heightPixels)
    val screenH = minOf(metrics.widthPixels, metrics.heightPixels)

    @Suppress("DEPRECATION")
    val display = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
    // R12(b): the stream can switch the panel to its fastest mode, so recommend against that rate.
    val current = DisplayModes.current(display)
    val supported = DisplayModes.supported(display)

    val report = remember {
        val caps = DecoderProbe.probe(MediaCodecInfoView.listDecoders())
        val defaults = DecoderProbe.recommend(caps, screenW, screenH, current, supported)
        buildReport(caps, defaults, screenW, screenH, current, supported)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sonda de decoder", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = { copyToClipboard(context, report) }) {
            Text("Copiar como texto")
        }
        Text(report, style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun buildReport(
    caps: List<DecoderCaps>,
    defaults: StreamDefaults,
    screenW: Int,
    screenH: Int,
    current: DisplayModeInfo,
    supported: List<DisplayModeInfo>,
): String = buildString {
    val maxHz = DisplayModes.maxRefreshHz(current, supported)
    val rates = supported.filter { it.sameResolution(current) }.map { Math.round(it.refreshHz) }.distinct().sorted()
    appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    appendLine("Tela: ${screenW}x$screenH @ ${Math.round(maxHz)} Hz (modo atual ${Math.round(current.refreshHz)} Hz; modos: ${rates.joinToString("/")} Hz)")
    appendLine()
    appendLine("Recomendado: ${defaults.mime} ${defaults.width}x${defaults.height} @ ${defaults.fps} fps")
    appendLine("Decoder escolhido: ${DecoderProbe.pickDecoder(caps, defaults.mime)?.codecName ?: "nenhum"}")
    appendLine()
    appendLine("Decoders de vídeo:")
    for (c in caps) {
        val fps = c.maxFpsAt1080p?.let { "%.0f".format(it) } ?: "sem suporte a 1080p"
        appendLine("- ${c.codecName} (${c.mime})")
        appendLine("    hardware: ${if (c.hardware) "sim" else "não"}")
        appendLine("    máximo: ${c.maxWidth}x${c.maxHeight}")
        appendLine("    fps máximo em 1080p: $fps")
        appendLine("    baixa latência: ${if (c.lowLatency) "sim" else "não"}")
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Duvo - sonda de decoder", text))
}
