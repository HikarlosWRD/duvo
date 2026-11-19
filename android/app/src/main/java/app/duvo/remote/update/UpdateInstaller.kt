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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Result of [UpdateInstaller.downloadVerified]. */
sealed interface DownloadResult {
    data class Ok(val file: File) : DownloadResult
    data class Failed(val message: String) : DownloadResult
}

/**
 * Downloads the APK of a release, checks its SHA-256 against the release's SHA256SUMS.txt and hands it to
 * Android's installer. Android itself refuses the update unless it is signed with the same key as the
 * installed app, so a swapped file cannot replace Duvo.
 */
object UpdateInstaller {
    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        return c
    }

    /** Blocking; call off the main thread. [onProgress] gets 0..100 (or -1 when the size is unknown). */
    fun downloadVerified(ctx: Context, r: ReleaseInfo, onProgress: (Int) -> Unit = {}): DownloadResult {
        val apk = r.apkUrl ?: return DownloadResult.Failed("A versão ${r.version} não traz um APK.")
        val sums = r.sumsUrl ?: return DownloadResult.Failed("A versão ${r.version} não traz o arquivo de verificação (SHA256SUMS.txt).")
        return try {
            val text = open(sums).let { c -> if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null }
                ?: return DownloadResult.Failed("Não consegui baixar o arquivo de verificação.")
            val expected = UpdateChecker.expectedHash(text, apk.substringAfterLast('/'))
                ?: return DownloadResult.Failed("O arquivo de verificação não lista ${apk.substringAfterLast('/')}.")
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val out = File(dir, "Duvo-${r.version}.apk")
            val c = open(apk)
            if (c.responseCode != 200) return DownloadResult.Failed("Download recusado (HTTP ${c.responseCode}).")
            val total = c.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        onProgress(if (total > 0) (done * 100 / total).toInt() else -1)
                    }
                }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) {
                out.delete()
                return DownloadResult.Failed("O arquivo baixado não confere com o hash publicado. Atualização cancelada.")
            }
            DownloadResult.Ok(out)
        } catch (e: Exception) {
            DownloadResult.Failed("Falha no download: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Opens Android's installer for [apk]; if "install unknown apps" is off for Duvo, opens that setting instead. */
    fun install(ctx: Context, apk: File): String? {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return "Permita \"Instalar apps desconhecidos\" para o Duvo e toque em Atualizar de novo."
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.clipboard", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return null
    }
}
