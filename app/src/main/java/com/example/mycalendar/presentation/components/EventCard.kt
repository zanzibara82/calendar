package com.example.mycalendar.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mycalendar.R
import com.example.mycalendar.presentation.common.labelRes
import com.example.mycalendar.presentation.common.timeFormatter
import com.example.mycalendar.presentation.theme.AppShapes
import com.example.mycalendar.presentation.theme.AppSizes
import com.example.mycalendar.presentation.theme.AppSpacing
import com.example.mycalendar.presentation.theme.CardTypography
import com.example.mycalendar.presentation.theme.LocalIsDarkTheme
import java.time.Instant
import java.time.ZoneId

/**
 * Карточка события: полоса провайдера, колонка времени, название, чипы и индикаторы.
 * Единый компонент для видов Месяц/Неделя/День — они различаются только группировкой.
 */
@Composable
fun EventCard(
    occurrence: EventOccurrence,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showProviderChip: Boolean = true,
) {
    val event = occurrence.event
    val dark = LocalIsDarkTheme.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = AppShapes.card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (dark) {
            null
        } else {
            BorderStroke(AppSizes.stroke, MaterialTheme.colorScheme.outline)
        },
    ) {
        // Полоса провайдера обязана тянуться на всю высоту карточки. Без IntrinsicSize.Min
        // высота ряда обёртки не ограничена, и fillMaxHeight внутри него схлопывает полосу в 0 dp.
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(AppSpacing.providerStripe)
                    .background(providerColor(event.calendarProvider)),
            )
            Column(
                modifier = Modifier.width(AppSizes.timeColumn),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = timeText(occurrence.startMillis),
                    style = CardTypography.time,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = timeText(occurrence.endMillis),
                    style = CardTypography.time,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = AppSpacing.cardHPad, vertical = AppSpacing.cardVPad),
            ) {
                Text(
                    text = event.title,
                    style = CardTypography.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showProviderChip) ProviderChip(event.calendarProvider)
                    event.tags.forEach { tag ->
                        TagChip(stringResource(tag.labelRes))
                    }
                }
            }
            Row(
                modifier = Modifier.padding(end = AppSpacing.cardHPad),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (event.reminderMinutes.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(AppSizes.iconMedium),
                    )
                }
                if (occurrence.isRecurring) {
                    Icon(
                        imageVector = Icons.Filled.Repeat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(AppSizes.iconMedium),
                    )
                }
                if (event.voiceNotePath != null) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(AppSizes.iconMedium),
                    )
                }
                if (!event.textNote.isNullOrBlank()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.StickyNote2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(AppSizes.iconMedium),
                    )
                }
            }
        }
    }
}

private fun timeText(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timeFormatter)
