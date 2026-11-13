package com.george.antirrobo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RedMagicRed = Color(0xFFE10600)
private val RedSoft = Color(0xFFFF8A80)
private val Bg = Color(0xFF0D0D0D)
private val Surface = Color(0xFF1A1A1A)
private val SurfaceHi = Color(0xFF242424)

private val Scheme = darkColorScheme(
    primary = RedMagicRed,
    onPrimary = Color.White,
    secondary = RedSoft,
    onSecondary = Color.Black,
    background = Bg,
    onBackground = Color(0xFFEEEEEE),
    surface = Surface,
    onSurface = Color(0xFFEEEEEE),
    surfaceVariant = SurfaceHi,
    onSurfaceVariant = Color(0xFFCCCCCC),
    error = Color(0xFFFF5252),
    outline = Color(0xFF444444),
)

@Composable
fun AntirroboTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
