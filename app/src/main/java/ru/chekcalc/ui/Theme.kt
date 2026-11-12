package ru.chekcalc.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Палитра из макета: тёплая бумага, тёмный текст, один зелёный акцент.
private val Paper = Color(0xFFF6F1E7)
private val Ink = Color(0xFF17150F)
private val Green = Color(0xFF14503C)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color(0xFFF3EFE5),
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FBFA0),
    onPrimary = Color(0xFF0E2C22),
    background = Color(0xFF14120E),
    onBackground = Color(0xFFEDE7DC),
    surface = Color(0xFF1D1A15),
    onSurface = Color(0xFFEDE7DC),
)

@Composable
fun ChekCalcTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
