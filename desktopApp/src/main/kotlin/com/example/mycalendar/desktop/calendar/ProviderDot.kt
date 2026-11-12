package com.example.mycalendar.desktop.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mycalendar.desktop.theme.LocalDesktopColors
import com.example.mycalendar.desktop.theme.providerColor
import com.example.mycalendar.domain.model.CalendarProvider

/**
 * Точка события в клетке месяца. Обводка — как на Android: на тонированном фоне
 * выходного и праздника часть цветов провайдеров не держит порог графики WCAG 3.0.
 */
@Composable
fun ProviderDot(provider: CalendarProvider, modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .size(5.dp)
            .background(providerColor(provider), CircleShape)
            .border(1.dp, LocalDesktopColors.current.onSurfaceVariant, CircleShape),
    )
}
