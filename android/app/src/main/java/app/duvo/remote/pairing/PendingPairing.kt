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

/**
 * Hands the scanned [PairPayload] (with its OTP/passphrase) from the QR screen to the pairing flow.
 * Application-scoped and in memory only: never persisted, and cleared once the flow ends.
 */
class PendingPairing {
    @Volatile var payload: PairPayload? = null
        private set

    fun put(p: PairPayload) { payload = p }

    fun clear() { payload = null }
}

/**
 * Question shown before pairing from a deep link (R12a): any app or web page can fire duvo://pair,
 * so the user confirms the PC by name and every IP the link would contact.
 */
fun deepLinkConfirmText(p: PairPayload): String =
    "Parear com ${safeDisplayName(p.name)}?\nIPs: ${p.ips.joinToString()}"

/**
 * The host name comes from an untrusted link: control characters become spaces (no forged lines in the
 * dialog), invisible format characters (RTL overrides, zero-width) are dropped, whitespace is collapsed
 * and the result is capped at 40 chars.
 */
fun safeDisplayName(raw: String): String {
    val cleaned = raw
        .replace(Regex("""[\p{Cf}\p{Co}\p{Cn}]"""), "")
        .replace(Regex("""[\p{Cc}\p{Z}]+"""), " ")
        .trim()
    if (cleaned.codePointCount(0, cleaned.length) <= 40) return cleaned
    return cleaned.substring(0, cleaned.offsetByCodePoints(0, 39)) + "…"
}
