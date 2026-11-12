package com.example.mycalendar.desktop.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.mycalendar.domain.holiday.DayMark

/**
 * Тёмная схема desktop-клиента. Числа совпадают с Android-палитрой (тёмная схема —
 * эталон, см. app/.../presentation/theme/Palette.kt): пока общий UI-модуль не выделен,
 * значения живут в двух местах и меняются только вместе.
 */
object Palette {
    val Background = Color(0xFF0B1220)
    val Surface = Color(0xFF101B2E)
    val Card = Color(0xFF1B2A44)
    val OnSurface = Color(0xFFE8EEF9)
    val OnSurfaceVariant = Color(0xFF9FB0CC)
    val Primary = Color(0xFF4790FF)
    val OnPrimary = Color(0xFF04122B)
    val Outline = Color(0xFF2C3C58)
    val WeekendContainer = Color(0xFF173F52)
    val HolidayContainer = Color(0xFF4A2E10)
}

/** Тон клетки дня: выходной — бирюзовый, праздник — янтарный. */
fun dayContainer(mark: DayMark?): Color = when (mark) {
    DayMark.WEEKEND -> Palette.WeekendContainer
    DayMark.HOLIDAY -> Palette.HolidayContainer
    null -> Palette.Card
}

@Composable
fun DesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Palette.Background,
            surface = Palette.Surface,
            onSurface = Palette.OnSurface,
            onSurfaceVariant = Palette.OnSurfaceVariant,
            primary = Palette.Primary,
            onPrimary = Palette.OnPrimary,
            outline = Palette.Outline,
        ),
        content = content,
    )
}
