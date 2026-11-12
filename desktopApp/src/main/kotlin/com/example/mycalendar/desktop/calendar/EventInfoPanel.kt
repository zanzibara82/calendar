package com.example.mycalendar.desktop.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.mycalendar.desktop.theme.EventCardGradient
import com.example.mycalendar.desktop.theme.LocalDesktopColors
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.EventTag
import com.example.mycalendar.domain.model.RepeatRule
import java.time.LocalDate
import java.time.ZoneId

private val PANEL_WIDTH = 320.dp

/** Карточка события целиком: дата дня, время, повтор, описания и метки, кнопки правки и удаления. */
@Composable
fun EventInfoPanel(
    event: Event,
    date: LocalDate,
    zone: ZoneId,
    onEdit: (Event) -> Unit,
    onDelete: (Event) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDesktopColors.current
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(PANEL_WIDTH)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.card)
            .testTag("event-info"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Brush.horizontalGradient(EventCardGradient)),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = event.title,
                color = colors.onSurface,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).testTag("event-info-title"),
            )
            IconButton(onClick = onClose, modifier = Modifier.testTag("event-info-close")) {
                Icon(Icons.Filled.Close, contentDescription = "Закрыть")
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InfoLine(dayTitle(date))
            InfoLine(timeRange(event.startTime, event.endTime, date, zone))
            event.repeatRule?.let { InfoLine(repeatLabel(it)) }
            event.description?.takeIf { it.isNotBlank() }?.let { InfoSection("Описание", it) }
            event.textNote?.takeIf { it.isNotBlank() }?.let { InfoSection("Заметка", it) }
            if (event.tags.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Метки",
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        event.tags.forEach { TagChip(tagLabel(it)) }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onEdit(event) },
                modifier = Modifier.weight(1f).testTag("event-info-edit"),
            ) {
                Text("Изменить")
            }
            TextButton(
                onClick = { onDelete(event) },
                colors = ButtonDefaults.textButtonColors(contentColor = colors.eventYandex),
                modifier = Modifier.testTag("event-info-delete"),
            ) {
                Text("Удалить")
            }
        }
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(
        text = text,
        color = LocalDesktopColors.current.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun InfoSection(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            color = LocalDesktopColors.current.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = value,
            color = LocalDesktopColors.current.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TagChip(label: String) {
    val colors = LocalDesktopColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = colors.onSecondaryContainer,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun repeatLabel(rule: RepeatRule): String = when (rule) {
    RepeatRule.DAILY -> "Ежедневно"
    RepeatRule.WEEKLY -> "Еженедельно"
    RepeatRule.MONTHLY -> "Ежемесячно"
    RepeatRule.YEARLY -> "Ежегодно"
}

internal fun tagLabel(tag: EventTag): String = when (tag) {
    EventTag.BIRTHDAY -> "День рождения"
    EventTag.DATE -> "Свидание"
    EventTag.CINEMA -> "Кино"
    EventTag.DOCTOR -> "Врач"
    EventTag.MEDICINE -> "Лекарства"
    EventTag.VACATION -> "Отпуск"
}
