package com.portal.kasa.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF34C759) // Kasa green — "on"
private val OnAccent = Color(0xFF06210F) // near-black green, for text/icons sitting on the accent
private val Bg = Color(0xFF0B0E11)
private val Surface = Color(0xFF161B21) // card (off)
private val SurfaceVariant = Color(0xFF1E252D) // raised chips/controls
private val OnBg = Color(0xFFEDEFF2)
private val Subtle = Color(0xFF9AA4AF) // secondary text / muted icons
private val Outline = Color(0xFF2A323B) // hairline card borders

private val Colors =
    darkColorScheme(
        primary = Accent,
        onPrimary = OnAccent,
        // Green secondary tones so tonal buttons/chips stay on-brand instead of Material's default purple.
        secondary = Accent,
        onSecondary = OnAccent,
        secondaryContainer = Color(0xFF173A24),
        onSecondaryContainer = Color(0xFF8FE6AB),
        background = Bg,
        surface = Surface,
        surfaceVariant = SurfaceVariant,
        onBackground = OnBg,
        onSurface = OnBg,
        onSurfaceVariant = Subtle,
        outline = Outline,
    )

@Composable
fun KasaTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Colors, content = content)
