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
package app.duvo.remote.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.duvo.remote.R

/** How the app picks its colours: dark by default; the user can choose light or follow the system. */
enum class Appearance(val label: String) {
    DARK("Escuro"),
    LIGHT("Claro"),
    SYSTEM("Automático"),
    ;

    companion object {
        private const val FILE = "duvo_ui"
        private const val KEY = "appearance"

        fun load(context: Context): Appearance = runCatching {
            valueOf(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, DARK.name).orEmpty())
        }.getOrDefault(DARK)

        fun save(context: Context, a: Appearance) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, a.name).apply()
        }
    }
}

/** The one accent (signal amber) and the colours that are not part of Material's scheme. */
object Duvo {
    val Amber = Color(0xFFF2851F)
    val OnAmber = Color(0xFF1A1204)
    val OkDark = Color(0xFF5BCB8A)
    val OkLight = Color(0xFF1E7A44)
}

/** Status green that reads on the current ground. */
val LocalOk = staticCompositionLocalOf { Duvo.OkDark }

val PlexSans = FontFamily(
    Font(R.font.ibmplexsans_regular, FontWeight.Normal),
    Font(R.font.ibmplexsans_medium, FontWeight.Medium),
    Font(R.font.ibmplexsans_semibold, FontWeight.SemiBold),
    Font(R.font.ibmplexsans_semibold, FontWeight.Bold),
)

/** For addresses, status lines and stats. */
val PlexMono = FontFamily(
    Font(R.font.ibmplexmono_regular, FontWeight.Normal),
    Font(R.font.ibmplexmono_medium, FontWeight.Medium),
)

private val DarkScheme = darkColorScheme(
    primary = Duvo.Amber,
    onPrimary = Duvo.OnAmber,
    primaryContainer = Color(0xFF3D2108),
    onPrimaryContainer = Color(0xFFFFD9B0),
    secondary = Color(0xFFECEDEF),
    onSecondary = Color(0xFF0E0F11),
    secondaryContainer = Color(0xFF3D2108),
    onSecondaryContainer = Color(0xFFFFD9B0),
    tertiary = Duvo.OkDark,
    background = Color(0xFF0E0F11),
    onBackground = Color(0xFFECEDEF),
    surface = Color(0xFF0E0F11),
    onSurface = Color(0xFFECEDEF),
    surfaceVariant = Color(0xFF16171A),
    onSurfaceVariant = Color(0xFF9B9EA6),
    surfaceContainerLowest = Color(0xFF0E0F11),
    surfaceContainerLow = Color(0xFF16171A),
    surfaceContainer = Color(0xFF16171A),
    surfaceContainerHigh = Color(0xFF1E1F23),
    surfaceContainerHighest = Color(0xFF1E1F23),
    outline = Color(0xFF2B2D32),
    outlineVariant = Color(0xFF2B2D32),
    error = Color(0xFFF2766B),
    onError = Color(0xFF1A0705),
)

private val LightScheme = lightColorScheme(
    // Amber stays the fill of the main button; text and links use a darker amber that passes contrast on cream.
    primary = Color(0xFF9A4D00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Duvo.Amber,
    onPrimaryContainer = Duvo.OnAmber,
    secondary = Color(0xFF1A1B1E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE3C9),
    onSecondaryContainer = Color(0xFF4A2600),
    tertiary = Duvo.OkLight,
    background = Color(0xFFF3F2EE),
    onBackground = Color(0xFF1A1B1E),
    surface = Color(0xFFF3F2EE),
    onSurface = Color(0xFF1A1B1E),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF5E6068),
    surfaceContainerLowest = Color(0xFFF3F2EE),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEAE8E2),
    surfaceContainerHighest = Color(0xFFEAE8E2),
    outline = Color(0xFFDDDAD2),
    outlineVariant = Color(0xFFDDDAD2),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private fun plex(size: Int, weight: FontWeight = FontWeight.Normal, line: Int = size + 6) =
    TextStyle(fontFamily = PlexSans, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

private val DuvoTypography = Typography(
    displayLarge = plex(48, FontWeight.SemiBold, 54),
    headlineLarge = plex(30, FontWeight.SemiBold, 36),
    headlineMedium = plex(26, FontWeight.SemiBold, 32),
    headlineSmall = plex(22, FontWeight.SemiBold, 28),
    titleLarge = plex(22, FontWeight.SemiBold, 28),
    titleMedium = plex(17, FontWeight.SemiBold, 24),
    titleSmall = plex(14, FontWeight.SemiBold, 20),
    bodyLarge = plex(16),
    bodyMedium = plex(15, line = 22),
    bodySmall = plex(13, line = 18),
    labelLarge = plex(15, FontWeight.Medium, 20),
    labelMedium = plex(13, FontWeight.Medium, 18),
    labelSmall = plex(11, FontWeight.Medium, 16),
)

private val DuvoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Colour scheme for [appearance]; [systemDark] only matters for [Appearance.SYSTEM]. */
fun duvoColorScheme(appearance: Appearance, systemDark: Boolean): ColorScheme =
    if (appearance == Appearance.LIGHT || (appearance == Appearance.SYSTEM && !systemDark)) LightScheme else DarkScheme

@Composable
fun DuvoTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = appearance == Appearance.DARK || (appearance == Appearance.SYSTEM && isSystemInDarkTheme())
    CompositionLocalProvider(LocalOk provides if (dark) Duvo.OkDark else Duvo.OkLight) {
        MaterialTheme(
            colorScheme = duvoColorScheme(appearance, isSystemInDarkTheme()),
            typography = DuvoTypography,
            shapes = DuvoShapes,
            content = content,
        )
    }
}
