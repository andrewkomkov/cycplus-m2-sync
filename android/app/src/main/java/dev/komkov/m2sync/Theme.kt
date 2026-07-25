package dev.komkov.m2sync

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
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

/**
 * Material 3 Expressive — язык оформления Android 16+.
 *
 * От обычной темы отличается моушен-схемой: она задаёт пружинную анимацию всем
 * компонентам разом, поэтому нажатия и появления ощущаются живыми без ручной
 * настройки каждого перехода.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun M2Theme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    // Material You: цвета берём из обоев пользователя.
    val colors = runCatching {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    }.getOrElse { if (dark) FallbackDark else FallbackLight }

    MaterialExpressiveTheme(
        colorScheme = colors,
        // Выразительная схема: заметнее отскок, чем у standard.
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
