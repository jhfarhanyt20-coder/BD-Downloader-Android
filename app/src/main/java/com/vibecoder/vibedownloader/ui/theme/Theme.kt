package com.vibecoder.vibedownloader.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vibecoder.vibedownloader.Prefs

/** Dark, Light, or follow the phone. */
enum class ThemeMode(val label: String) {
    System("System"), Light("Light"), Dark("Dark");

    companion object {
        fun from(s: String?): ThemeMode = entries.firstOrNull { it.name == s } ?: System
    }
}

/** Every colour the app draws with. Two palettes share the same purple-cyan accent. */
@Immutable
data class VdPalette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val card: Color,
    val cardHi: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val accentLight: Color,
    val accent2: Color,
    val green: Color,
    val amber: Color,
    val red: Color,
    val accentSoft: Color,
    val accentSoftLine: Color,
    val accentSelected: Color,
    val scrim: Color,
    val catVideo: Color,
    val catAudio: Color,
    val catDocs: Color,
    val catApk: Color,
    val catOther: Color,
)

val DarkPalette = VdPalette(
    dark = true,
    bg = Color(0xFF0D0E10),
    surface = Color(0xFF15171B),
    card = Color(0xFF1A1D22),
    cardHi = Color(0xFF22262D),
    line = Color(0xFF2A2E35),
    text = Color(0xFFECEDEF),
    muted = Color(0xFF9AA1A9),
    faint = Color(0xFF6E7681),
    accent = Color(0xFF6A3DF5),
    accentLight = Color(0xFF8B5CFF),
    accent2 = Color(0xFF00B8F0),
    green = Color(0xFF3DDC84),
    amber = Color(0xFFE4A11B),
    red = Color(0xFFE46A61),
    accentSoft = Color(0x226A3DF5),
    accentSoftLine = Color(0x336A3DF5),
    accentSelected = Color(0x336A3DF5),
    scrim = Color(0x99000000),
    catVideo = Color(0xFF8B5CFF),
    catAudio = Color(0xFFFF6FAE),
    catDocs = Color(0xFFF2A93B),
    catApk = Color(0xFF3DDC84),
    catOther = Color(0xFF00B8F0),
)

/** Soft white and grey surfaces, the same accent, text with comfortable contrast. */
val LightPalette = VdPalette(
    dark = false,
    bg = Color(0xFFF3F4F8),
    surface = Color(0xFFFFFFFF),
    card = Color(0xFFFAFAFD),
    cardHi = Color(0xFFFFFFFF),
    line = Color(0xFFE2E4EC),
    text = Color(0xFF16181D),
    muted = Color(0xFF5B6270),
    faint = Color(0xFF8A909C),
    accent = Color(0xFF6A3DF5),
    accentLight = Color(0xFF6A3DF5),
    accent2 = Color(0xFF0794C6),
    green = Color(0xFF16A05A),
    amber = Color(0xFFB4740F),
    red = Color(0xFFD2453C),
    accentSoft = Color(0x146A3DF5),
    accentSoftLine = Color(0x266A3DF5),
    accentSelected = Color(0x1F6A3DF5),
    scrim = Color(0x66000000),
    catVideo = Color(0xFF6A3DF5),
    catAudio = Color(0xFFD63F86),
    catDocs = Color(0xFFC77A0A),
    catApk = Color(0xFF16A05A),
    catOther = Color(0xFF0794C6),
)

private fun mix(a: VdPalette, b: VdPalette, t: Float) = VdPalette(
    dark = if (t < 0.5f) a.dark else b.dark,
    bg = lerp(a.bg, b.bg, t), surface = lerp(a.surface, b.surface, t), card = lerp(a.card, b.card, t),
    cardHi = lerp(a.cardHi, b.cardHi, t), line = lerp(a.line, b.line, t), text = lerp(a.text, b.text, t),
    muted = lerp(a.muted, b.muted, t), faint = lerp(a.faint, b.faint, t), accent = lerp(a.accent, b.accent, t),
    accentLight = lerp(a.accentLight, b.accentLight, t), accent2 = lerp(a.accent2, b.accent2, t),
    green = lerp(a.green, b.green, t), amber = lerp(a.amber, b.amber, t), red = lerp(a.red, b.red, t),
    accentSoft = lerp(a.accentSoft, b.accentSoft, t), accentSoftLine = lerp(a.accentSoftLine, b.accentSoftLine, t),
    accentSelected = lerp(a.accentSelected, b.accentSelected, t), scrim = lerp(a.scrim, b.scrim, t),
    catVideo = lerp(a.catVideo, b.catVideo, t), catAudio = lerp(a.catAudio, b.catAudio, t),
    catDocs = lerp(a.catDocs, b.catDocs, t), catApk = lerp(a.catApk, b.catApk, t), catOther = lerp(a.catOther, b.catOther, t),
)

/**
 * The app's colours. Every value reads the current palette (a Compose state),
 * so all existing `Vd.Xxx` call sites keep compiling and redraw by themselves
 * when the theme changes.
 */
object Vd {
    /** What the user picked in Settings. */
    var mode by mutableStateOf(ThemeMode.System)
    internal var palette by mutableStateOf(DarkPalette)

    val isDark: Boolean get() = palette.dark
    val Bg: Color get() = palette.bg
    val Surface: Color get() = palette.surface
    val Card: Color get() = palette.card
    val CardHi: Color get() = palette.cardHi
    val Line: Color get() = palette.line
    val Text: Color get() = palette.text
    val Muted: Color get() = palette.muted
    val Faint: Color get() = palette.faint
    val Accent: Color get() = palette.accent
    val AccentLight: Color get() = palette.accentLight
    val Accent2: Color get() = palette.accent2
    val Green: Color get() = palette.green
    val Amber: Color get() = palette.amber
    val Red: Color get() = palette.red
    val AccentSoft: Color get() = palette.accentSoft
    val AccentSoftLine: Color get() = palette.accentSoftLine
    val AccentSelected: Color get() = palette.accentSelected
    val Scrim: Color get() = palette.scrim
    val CatVideo: Color get() = palette.catVideo
    val CatAudio: Color get() = palette.catAudio
    val CatDocs: Color get() = palette.catDocs
    val CatApk: Color get() = palette.catApk
    val CatOther: Color get() = palette.catOther

    // solid colours only (no gradients); the names are kept so every call site stays the same
    val AccentGradient: Brush get() = androidx.compose.ui.graphics.SolidColor(palette.accent)
    val GlowGradient: Brush get() = androidx.compose.ui.graphics.SolidColor(palette.accentSoft)

    fun target(mode: ThemeMode, systemDark: Boolean): VdPalette = when (mode) {
        ThemeMode.Dark -> DarkPalette
        ThemeMode.Light -> LightPalette
        ThemeMode.System -> if (systemDark) DarkPalette else LightPalette
    }

    fun systemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Called before the first frame so the window never flashes the wrong colours. */
    fun prepare(context: Context) {
        mode = ThemeMode.from(Prefs(context).themeMode)
        palette = target(mode, systemDark(context))
    }

    fun setMode(context: Context, m: ThemeMode) {
        Prefs(context).themeMode = m.name
        mode = m
    }
}

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

/** Status and navigation bar icons that match the theme (light icons on dark, dark icons on light). */
fun ComponentActivity.applySystemBars(dark: Boolean) {
    val t = android.graphics.Color.TRANSPARENT
    val style = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
    runCatching { enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style) }
    runCatching { window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Vd.Bg.toArgb())) }
}

@Composable
fun VibeTheme(translucent: Boolean = false, content: @Composable () -> Unit) {
    val target = Vd.target(Vd.mode, isSystemInDarkTheme())
    // a smooth colour cross-fade when the theme changes (written from the frame clock, never mid-composition)
    LaunchedEffect(target) {
        val from = Vd.palette
        if (from != target) {
            animate(0f, 1f, animationSpec = tween(450)) { v, _ -> Vd.palette = mix(from, target, v) }
        }
        Vd.palette = target
    }
    val activity = LocalContext.current as? ComponentActivity
    LaunchedEffect(target.dark) {
        activity?.let { a ->
            val t = android.graphics.Color.TRANSPARENT
            val style = if (target.dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
            runCatching { a.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style) }
            if (!translucent) runCatching { a.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(target.bg.toArgb())) }
        }
    }
    val p = Vd.palette
    val scheme = if (p.dark) darkColorScheme(
        primary = p.accent, onPrimary = Color.White, secondary = p.accent2, onSecondary = Color.Black,
        background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.card, onSurfaceVariant = p.muted, outline = p.line, error = p.red,
        surfaceContainer = p.card, surfaceContainerHigh = p.cardHi, surfaceContainerLow = p.surface,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = Color.White, secondary = p.accent2, onSecondary = Color.White,
        background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.card, onSurfaceVariant = p.muted, outline = p.line, error = p.red,
        surfaceContainer = p.card, surfaceContainerHigh = p.cardHi, surfaceContainerLow = p.surface,
    )
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
