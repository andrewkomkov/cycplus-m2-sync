package dev.komkov.m2sync

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Запасная палитра, если системных динамических цветов почему-то нет. */
private val FallbackDark = darkColorScheme(
    primary = Color(0xFF7FD1AE),
    secondary = Color(0xFF9CCAFF),
    tertiary = Color(0xFFFFB4A9),
)

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF00695C),
    secondary = Color(0xFF00639B),
    tertiary = Color(0xFFB3261E),
)

@Composable
fun M2Theme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    // Material You: цвета берём из обоев пользователя.
    val colors = runCatching {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    }.getOrElse { if (dark) FallbackDark else FallbackLight }

    MaterialTheme(colorScheme = colors, content = content)
}
