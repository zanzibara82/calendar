package com.example.mycalendar.presentation.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.mycalendar.domain.holiday.DayMark
import com.example.mycalendar.domain.model.ThemeMode

/** Светлая схема — производная от эталонной тёмной по тем же ролям. */
private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    secondaryContainer = LightSecondaryContainer,
    tertiary = LightTertiary,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightCard,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = LightError,
)

/** Тёмная схема — эталон визуального языка (navy из макетов). */
private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    secondaryContainer = DarkSecondaryContainer,
    tertiary = DarkTertiary,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkCard,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = DarkError,
)

/**
 * Выбрана ли сейчас тёмная схема. Нужен компонентам, у которых внешний вид зависит от
 * схемы, а не только от ролей `ColorScheme`: граница карточки есть только в светлой.
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * Тон поверхности дня по метке. Обычная функция, не composable: тест контраста и
 * Windows-версия собирают цвета без CompositionLocal.
 */
fun dayMarkContainer(mark: DayMark, dark: Boolean): Color = when (mark) {
    DayMark.WEEKEND -> if (dark) DarkDayWeekendContainer else LightDayWeekendContainer
    DayMark.HOLIDAY -> if (dark) DarkDayHolidayContainer else LightDayHolidayContainer
}

/** Тон буднего дня: день без метки тоже получает собственный фон, а не поверхность. */
fun weekdayContainer(dark: Boolean): Color =
    if (dark) DarkDayWeekdayContainer else LightDayWeekdayContainer

/** Обводка клетки дня: держит границу при тоне будней, выходных и праздников. */
fun dayBorder(dark: Boolean): Color = if (dark) DarkDayBorder else LightDayBorder

/**
 * Тон дня для текущей схемы. `null` — метки нет: вызывающий компонент решает сам,
 * красить ли поверхность (клетки красят будним тоном, шапка дня остаётся прозрачной).
 */
@Composable
fun dayMarkColor(mark: DayMark?): Color? =
    mark?.let { dayMarkContainer(it, LocalIsDarkTheme.current) }

/**
 * Фон клетки дня по метке. Нет метки — будний тон: клетка не сливается с фоном экрана.
 */
@Composable
fun dayContainerColor(mark: DayMark?): Color =
    mark?.let { dayMarkContainer(it, LocalIsDarkTheme.current) }
        ?: weekdayContainer(LocalIsDarkTheme.current)

@Composable
fun dayBorderColor(): Color = dayBorder(LocalIsDarkTheme.current)

/**
 * Тема приложения. Схема цветов детерминирована палитрой: динамические цвета обоев
 * (Material You) сознательно убраны — они разрушали бы визуальный язык, общий с Windows-версией.
 */
@Composable
fun MyCalendarTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (darkTheme) DarkColors else LightColors

    // Цвет иконок статус-бара — по контрасту со схемой
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
