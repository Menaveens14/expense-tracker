package com.personal.expensetracker

import android.graphics.Typeface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

class Pal(val bg: Long, val surface: Long, val line: Long, val muted: Long, val fg: Long)

val LightPals = linkedMapOf(
    "Warm" to Pal(0xFFF7F7F5, 0xFFFFFFFF, 0xFFDADAD4, 0xFF5F5F5A, 0xFF1A1A1A),
    "Cool" to Pal(0xFFF1F5F9, 0xFFFFFFFF, 0xFFCBD5E1, 0xFF475569, 0xFF0F172A),
    "Pure white" to Pal(0xFFFFFFFF, 0xFFF5F5F5, 0xFFE0E0E0, 0xFF5F5F5F, 0xFF111111)
)
val DarkPals = linkedMapOf(
    "Charcoal" to Pal(0xFF121212, 0xFF1E1E1E, 0xFF3A3A38, 0xFFA8A8A3, 0xFFEDEDED),
    "Navy" to Pal(0xFF0E1220, 0xFF181D30, 0xFF2B3350, 0xFFA3A9C2, 0xFFECEEF6),
    "Black" to Pal(0xFF000000, 0xFF111111, 0xFF2A2A2A, 0xFFA0A0A0, 0xFFEEEEEE)
)
// name -> (light-mode colour, dark-mode colour)
val Accents = linkedMapOf(
    "Teal" to (0xFF0F766E to 0xFF4FD1C5),
    "Indigo" to (0xFF4F46E5 to 0xFF818CF8),
    "Orange" to (0xFFC2410C to 0xFFFB923C),
    "Rose" to (0xFFE11D48 to 0xFFFB7185),
    "Green" to (0xFF15803D to 0xFF4ADE80),
    "Slate" to (0xFF475569 to 0xFF94A3B8)
)
val Fonts = linkedMapOf(
    "Roboto" to FontFamily.Default,
    "Condensed" to FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL)),
    "Serif" to FontFamily.Serif,
    "Mono" to FontFamily.Monospace
)

@Composable
fun AppTheme(p: Prefs, content: @Composable () -> Unit) {
    val dark = when (p.mode) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }
    val pal = (if (dark) DarkPals[p.darkStyle] else LightPals[p.lightStyle]) ?: LightPals.getValue("Warm")
    val ac = Accents[p.accent] ?: Accents.getValue("Teal")
    val acc = Color(if (dark) ac.second else ac.first)
    val surf = Color(pal.surface)
    val cs = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = acc, onPrimary = Color(if (dark) pal.bg else 0xFFFFFFFF),
        background = Color(pal.bg), onBackground = Color(pal.fg),
        surface = Color(pal.bg), onSurface = Color(pal.fg), onSurfaceVariant = Color(pal.muted),
        outline = Color(pal.line), outlineVariant = Color(pal.line),
        surfaceContainer = surf, surfaceContainerLow = surf, surfaceContainerHigh = surf, surfaceContainerHighest = surf,
        secondaryContainer = acc.copy(alpha = 0.2f), onSecondaryContainer = Color(pal.fg)
    )
    val ff = Fonts[p.font] ?: FontFamily.Default
    val b = Typography()
    fun TextStyle.f() = copy(fontFamily = ff)
    val ty = Typography(
        displayLarge = b.displayLarge.f(), displayMedium = b.displayMedium.f(), displaySmall = b.displaySmall.f(),
        headlineLarge = b.headlineLarge.f(), headlineMedium = b.headlineMedium.f(), headlineSmall = b.headlineSmall.f(),
        titleLarge = b.titleLarge.f(), titleMedium = b.titleMedium.f(), titleSmall = b.titleSmall.f(),
        bodyLarge = b.bodyLarge.f(), bodyMedium = b.bodyMedium.f(), bodySmall = b.bodySmall.f(),
        labelLarge = b.labelLarge.f(), labelMedium = b.labelMedium.f(), labelSmall = b.labelSmall.f()
    )
    MaterialTheme(colorScheme = cs, typography = ty, content = content)
}
