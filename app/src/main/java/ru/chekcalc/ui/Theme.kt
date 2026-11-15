package ru.chekcalc.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
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

/**
 * Цвета, которых нет в Material-схеме, но они есть в макете:
 * зелёная карточка-кнопка, линии групп, приглушённый текст и подписи.
 */
data class ChekPalette(
    val hero: Color,
    val heroInk: Color,
    val line: Color,
    val muted: Color,
    val faint: Color,
)

private val LightPalette = ChekPalette(
    hero = Green,
    heroInk = Color(0xFFF3EFE5),
    line = Color(0xFFE7DFCF),
    muted = Color(0xFF726C5E),
    faint = Color(0xFF9A9384),
)

private val DarkPalette = ChekPalette(
    hero = Color(0xFF1C6A4F),
    heroInk = Color(0xFFF3EFE5),
    line = Color(0xFF2E2A22),
    muted = Color(0xFFA79F8E),
    faint = Color(0xFF6E6758),
)

val LocalChekPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun ChekCalcTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalChekPalette provides if (darkTheme) DarkPalette else LightPalette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}
