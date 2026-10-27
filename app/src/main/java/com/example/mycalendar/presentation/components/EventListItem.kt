package com.example.mycalendar.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.presentation.common.timeFormatter
import com.example.mycalendar.presentation.theme.EventGoogleColor
import com.example.mycalendar.presentation.theme.EventLocalColor
import com.example.mycalendar.presentation.theme.EventYandexColor
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

@Composable
fun providerColor(provider: CalendarProvider): Color = when (provider) {
    CalendarProvider.LOCAL -> EventLocalColor
    CalendarProvider.GOOGLE -> EventGoogleColor
    CalendarProvider.YANDEX -> EventYandexColor
}

/**
 * Карточка события в списках Month/Week/Day.
 */
@Composable
fun EventListItem(
    occurrence: EventOccurrence,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Цветной маркер провайдера (local/google/yandex)
            Spacer(
                modifier = Modifier
                    .size(10.dp)
                    .background(providerColor(occurrence.event.calendarProvider), CircleShape),
            )
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = occurrence.event.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatTimeRange(occurrence),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Индикаторы: повтор, голосовая заметка, текстовая заметка
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (occurrence.isRecurring) {
                    Icon(
                        imageVector = Icons.Filled.Repeat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (occurrence.event.voiceNotePath != null) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (!occurrence.event.textNote.isNullOrBlank()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.StickyNote2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
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
    subtitle: String? = null,
    modifier: Modifier = Modifier,
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
