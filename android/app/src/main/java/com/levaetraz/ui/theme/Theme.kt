package com.levaetraz.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────
// PALETA
// Dark minimalista: um acento só, superfícies quase pretas e
// hierarquia por peso/cor em vez de caixas e divisores.
// ─────────────────────────────────────────────────────────────
object Palette {
    val Bg = Color(0xFF0A0A0A)
    val Surface = Color(0xFF141414)
    val SurfaceHigh = Color(0xFF1C1C1C)
    val Border = Color(0xFF262626)
    val BorderSoft = Color(0xFF1F1F1F)

    val Accent = Color(0xFF00C896)
    val AccentDim = Color(0xFF00785A)

    val Text = Color(0xFFEDEDED)
    val TextDim = Color(0xFF9A9A9A)
    val TextMuted = Color(0xFF5A5A5A)

    val Ok = Color(0xFF00C896)
    val Warn = Color(0xFFF0A500)
    val Err = Color(0xFFE05252)
}

/** Espaçamentos — mantidos generosos de propósito. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

private val DarkColors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.Bg,
    primaryContainer = Palette.AccentDim,
    onPrimaryContainer = Palette.Text,
    secondary = Palette.TextDim,
    onSecondary = Palette.Bg,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextDim,
    outline = Palette.Border,
    outlineVariant = Palette.BorderSoft,
    error = Palette.Err,
    onError = Palette.Bg,
)

/** Mono para números, status e tudo que precisa alinhar em coluna. */
val Mono = FontFamily.Monospace

private val AppTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Bold,
        fontSize = 9.sp, letterSpacing = 1.sp,
    ),
)

val LocalHaptics = staticCompositionLocalOf<Haptics> { error("Haptics não fornecido") }

@Composable
fun LevaeTrazTheme(haptics: Haptics, content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()   // o app é dark-only por design
    CompositionLocalProvider(LocalHaptics provides haptics) {
        MaterialTheme(
            colorScheme = DarkColors,
            typography = AppTypography,
            content = content,
        )
    }
}
