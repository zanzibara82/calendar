package com.example.mycalendar.desktop.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.mycalendar.domain.holiday.DayMark
import com.example.mycalendar.domain.model.CalendarProvider
import kotlinx.serialization.Serializable

/** Тема оформления: как на Android — по системе, всегда светлая или всегда тёмная. */
@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Роли палитры desktop-клиента. Числа совпадают с Android-палитрой
 * (app/.../presentation/theme/Palette.kt): пока общий UI-модуль не выделен,
 * значения живут в двух местах и меняются только вместе.
 */
data class DesktopColors(
    val background: Color,
    val surface: Color,
    val card: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val outline: Color,
    val weekend: Color,
    val holiday: Color,
    val weekday: Color,
    val dayBorder: Color,
    val eventLocal: Color,
    val eventGoogle: Color,
    val eventYandex: Color,
)

val DarkColors = DesktopColors(
    background = Color(0xFF0B1220),
    surface = Color(0xFF101B2E),
    card = Color(0xFF1B2A44),
    onSurface = Color(0xFFE8EEF9),
    onSurfaceVariant = Color(0xFF9FB0CC),
    primary = Color(0xFF4790FF),
    onPrimary = Color(0xFF04122B),
    secondaryContainer = Color(0xFF24334D),
    onSecondaryContainer = Color(0xFFE8EEF9),
    outline = Color(0xFF2C3C58),
    weekend = Color(0xFF1A4657),
    holiday = Color(0xFF5A3810),
    weekday = Color(0xFF203A63),
    dayBorder = Color(0xFF46658F),
    eventLocal = Color(0xFF7C5CFF),
    eventGoogle = Color(0xFF2E8DF5),
    eventYandex = Color(0xFFFF5252),
)

val LightColors = DesktopColors(
    background = Color(0xFFF4F7FC),
    surface = Color(0xFFFFFFFF),
    card = Color(0xFFEAF0FA),
    onSurface = Color(0xFF0F1B2D),
    onSurfaceVariant = Color(0xFF4A5A73),
    primary = Color(0xFF2766D6),
    onPrimary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE6F5),
    onSecondaryContainer = Color(0xFF06234F),
    outline = Color(0xFFC6D2E4),
    weekend = Color(0xFFA5E2C4),
    holiday = Color(0xFFF7CF88),
    weekday = Color(0xFFDCE6F5),
    dayBorder = Color(0xFFA6BAD6),
    eventLocal = Color(0xFF5B44D6),
    eventGoogle = Color(0xFF1A73E8),
    eventYandex = Color(0xFFDB3A22),
)

val LocalDesktopColors = staticCompositionLocalOf { DarkColors }
val LocalIsDarkTheme = staticCompositionLocalOf { true }

/** Шапка карточки события: фирменный градиент бирюза → синий → тёмно-синий. */
val EventCardGradient = listOf(Color(0xFF05CD99), Color(0xFF0075FF), Color(0xFF09235E))

/** Тон клетки дня: выходной — бирюзовый, праздник — янтарный, будни — голубые. */
@Composable
fun dayContainer(mark: DayMark?): Color {
    val colors = LocalDesktopColors.current
    return when (mark) {
        DayMark.WEEKEND -> colors.weekend
        DayMark.HOLIDAY -> colors.holiday
        null -> colors.weekday
    }
}

/** Цвет точки события. Разведён с primary, чтобы точка не читалась как «выбрано». */
@Composable
fun providerColor(provider: CalendarProvider): Color {
    val colors = LocalDesktopColors.current
    return when (provider) {
        CalendarProvider.LOCAL -> colors.eventLocal
        CalendarProvider.GOOGLE -> colors.eventGoogle
        CalendarProvider.YANDEX -> colors.eventYandex
    }
}

@Composable
fun DesktopTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(
            background = colors.background,
            surface = colors.surface,
            onSurface = colors.onSurface,
            onSurfaceVariant = colors.onSurfaceVariant,
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            secondaryContainer = colors.secondaryContainer,
            onSecondaryContainer = colors.onSecondaryContainer,
            outline = colors.outline,
        )
    } else {
        lightColorScheme(
            background = colors.background,
            surface = colors.surface,
            onSurface = colors.onSurface,
            onSurfaceVariant = colors.onSurfaceVariant,
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            secondaryContainer = colors.secondaryContainer,
            onSecondaryContainer = colors.onSecondaryContainer,
            outline = colors.outline,
        )
    }
    CompositionLocalProvider(
        LocalDesktopColors provides colors,
        LocalIsDarkTheme provides dark,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
