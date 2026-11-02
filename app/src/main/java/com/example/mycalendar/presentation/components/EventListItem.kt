package com.example.mycalendar.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.presentation.common.timeFormatter
import com.example.mycalendar.presentation.theme.EventGoogleColorDark
import com.example.mycalendar.presentation.theme.EventGoogleColorLight
import com.example.mycalendar.presentation.theme.EventLocalColorDark
import com.example.mycalendar.presentation.theme.EventLocalColorLight
import com.example.mycalendar.presentation.theme.EventYandexColorDark
import com.example.mycalendar.presentation.theme.EventYandexColorLight
import com.example.mycalendar.presentation.theme.LocalIsDarkTheme
import java.time.Instant
import java.time.ZoneId

/** Одно конкретное вхождение события (для повторяющихся — вычисленное вхождение). */
data class EventOccurrence(
    val event: com.example.mycalendar.domain.model.Event,
    val startMillis: Long,
    val endMillis: Long,
) {
    val isRecurring: Boolean get() = event.repeatRule != null
}

/** Цвет провайдера: полоса и точка карточки. Берётся из схемы, поэтому composable. */
@Composable
fun providerColor(provider: CalendarProvider): Color = when (provider) {
    CalendarProvider.LOCAL -> if (LocalIsDarkTheme.current) EventLocalColorDark else EventLocalColorLight
    CalendarProvider.GOOGLE -> if (LocalIsDarkTheme.current) EventGoogleColorDark else EventGoogleColorLight
    CalendarProvider.YANDEX -> if (LocalIsDarkTheme.current) EventYandexColorDark else EventYandexColorLight
}

fun formatTimeRange(occurrence: EventOccurrence): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(occurrence.startMillis).atZone(zone).format(timeFormatter)
    val end = Instant.ofEpochMilli(occurrence.endMillis).atZone(zone).format(timeFormatter)
    return "$start – $end"
}

/** Пустое состояние списка. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.padding(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
