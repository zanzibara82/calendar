package com.example.mycalendar.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.mycalendar.domain.model.ThemeMode

/** Светлая цветовая схема Material 3. */
private val LightColors = lightColorScheme(
    primary = IndigoPrimary,
    onPrimary = IndigoOnPrimary,
    primaryContainer = IndigoPrimaryContainer,
    onPrimaryContainer = IndigoOnPrimaryContainer,
    secondary = IndigoSecondary,
    secondaryContainer = IndigoSecondaryContainer,
    tertiary = IndigoTertiary,
    surface = IndigoSurface,
    surfaceVariant = IndigoSurfaceVariant,
    background = IndigoBackground,
    error = IndigoError,
)

/** Тёмная цветовая схема Material 3. */
private val DarkColors = darkColorScheme(
    primary = IndigoDarkPrimary,
    onPrimary = IndigoDarkOnPrimary,
    primaryContainer = IndigoDarkPrimaryContainer,
    onPrimaryContainer = IndigoDarkOnPrimaryContainer,
    secondary = IndigoDarkSecondary,
    secondaryContainer = IndigoDarkSecondaryContainer,
    tertiary = IndigoDarkTertiary,
    surface = IndigoDarkSurface,
    surfaceVariant = IndigoDarkSurfaceVariant,
    background = IndigoDarkBackground,
    error = IndigoDarkError,
)

/**
 * Тема приложения:
 *  - [themeMode] — выбор пользователя (Системная/Светлая/Тёмная), хранится в DataStore;
 *  - [dynamicColor] — Material You (динамические цвета из обоев), Android 12+.
 */
@Composable
fun MyCalendarTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    // Цвет иконок статус-бара — по контрасту со схемой
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
