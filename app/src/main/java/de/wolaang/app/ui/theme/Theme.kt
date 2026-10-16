package de.wolaang.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WolaangGreen = Color(0xFF2E7D32)
private val WolaangGreenLight = Color(0xFF60AD5E)
private val WolaangGreenDark = Color(0xFF005005)

private val LightColors = lightColorScheme(
    primary = WolaangGreen,
    primaryContainer = WolaangGreenLight,
    onPrimary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = WolaangGreenLight,
    primaryContainer = WolaangGreenDark,
    onPrimary = Color.Black,
)

@Composable
fun WolaangTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
