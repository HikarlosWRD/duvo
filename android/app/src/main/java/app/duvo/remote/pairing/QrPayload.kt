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

package app.duvo.remote.pairing

import java.net.URLDecoder

data class PairPayload(
    val ips: List<String>,
    val port: Int,
    val otp: String,
    val passphrase: String,
    val certSha256: String,
    val name: String,
)

/** Parses the pairing URI shown by the Windows host: duvo://pair?v=1&h=..&p=..&otp=..&pp=..&fp=..&n=.. */
object QrPayload {
    private const val PREFIX = "duvo://pair?"
    const val DEFAULT_PORT = 40989
    private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")
    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
    private val PASSPHRASE = Regex("^[A-Za-z0-9_-]{16,64}$")
    private val OTP = Regex("^\\d{4}$")

    fun parse(uri: String): Result<PairPayload> = runCatching {
        val text = uri.trim()
        require(text.startsWith(PREFIX, ignoreCase = true)) { "Esquema invalido" }
        val q = text.substring(PREFIX.length).substringBefore('#')
        val params = HashMap<String, String>()
        for (part in q.split('&')) {
            if (part.isEmpty()) continue
            params[part.substringBefore('=')] = part.substringAfter('=', "")
        }
        require(params["v"] == "1") { "Versao nao suportada" }
        val ips = params["h"].orEmpty().split(',').map { it.trim() }
        require(ips.isNotEmpty() && ips.all { IPV4.matches(it) }) { "IP invalido" }
        val port = params["p"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Porta invalida") } ?: DEFAULT_PORT
        require(port in 1..65535) { "Porta invalida" }
        val otp = params["otp"].orEmpty()
        require(OTP.matches(otp)) { "PIN invalido" }
        val pp = params["pp"].orEmpty()
        require(PASSPHRASE.matches(pp)) { "Senha ausente" }
        val fp = params["fp"].orEmpty()
        require(HEX64.matches(fp)) { "Fingerprint invalido" }
        val name = URLDecoder.decode(params["n"].orEmpty().replace("+", "%2B"), "UTF-8")
        PairPayload(ips, port, otp, pp, fp.lowercase(), name)
    }
}
