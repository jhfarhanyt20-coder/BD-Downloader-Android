package com.vibecoder.vibedownloader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The same dark, glossy palette as VibeDownloader for Windows. */
object Vd {
    val Bg = Color(0xFF0D0E10)
    val Surface = Color(0xFF15171B)
    val Card = Color(0xFF1A1D22)
    val CardHi = Color(0xFF22262D)
    val Line = Color(0xFF2A2E35)
    val Text = Color(0xFFECEDEF)
    val Muted = Color(0xFF9AA1A9)
    val Faint = Color(0xFF6E7681)
    val Accent = Color(0xFF6A3DF5)
    val AccentLight = Color(0xFF8B5CFF)
    val Accent2 = Color(0xFF00B8F0)
    val Green = Color(0xFF3DDC84)
    val Amber = Color(0xFFE4A11B)
    val Red = Color(0xFFE46A61)

    val AccentGradient = Brush.linearGradient(listOf(AccentLight, Accent2))
    val GlowGradient = Brush.verticalGradient(listOf(Color(0x336A3DF5), Color(0x0000B8F0)))
}

private val Scheme = darkColorScheme(
    primary = Vd.Accent,
    onPrimary = Color.White,
    secondary = Vd.Accent2,
    onSecondary = Color.Black,
    background = Vd.Bg,
    onBackground = Vd.Text,
    surface = Vd.Surface,
    onSurface = Vd.Text,
    surfaceVariant = Vd.Card,
    onSurfaceVariant = Vd.Muted,
    outline = Vd.Line,
    error = Vd.Red,
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

@Composable
fun VibeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
