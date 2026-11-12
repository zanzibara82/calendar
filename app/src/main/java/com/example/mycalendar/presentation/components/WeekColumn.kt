package com.example.mycalendar.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mycalendar.R
import com.example.mycalendar.domain.holiday.DayMark
import com.example.mycalendar.presentation.common.weekColDateFormatter
import com.example.mycalendar.presentation.theme.AppShapes
import com.example.mycalendar.presentation.theme.AppSizes
import com.example.mycalendar.presentation.theme.AppSpacing
import com.example.mycalendar.presentation.theme.WeekColumnTypography
import com.example.mycalendar.presentation.theme.dayBorderColor
import com.example.mycalendar.presentation.theme.dayContainerColor
import java.time.LocalDate

/**
 * Карточка дня в недельном столбце: строка заголовка, до AppSizes.weekVisibleCells строк
 * событий и «+N ещё» с переходом в вид «День». Пустой день остаётся карточкой, но в ней
 * одна строка заголовка вместо пустого тела.
 */
@Composable
fun WeekDayColumn(
    date: LocalDate,
    dayLabel: String,
    events: List<EventOccurrence>,
    onEventClick: (String) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    isToday: Boolean = date == LocalDate.now(),
    mark: DayMark? = null,
) {
    val visible = events.sortedBy { it.startMillis }.take(AppSizes.weekVisibleCells)
    val extra = events.size - visible.size
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(dayMarkTag(WEEK_CARD_TAG, date, mark)),
        shape = AppShapes.card,
        colors = CardDefaults.cardColors(
            containerColor = dayContainerColor(mark),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(AppSizes.stroke, dayBorderColor()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.cardHPad, vertical = AppSpacing.cardVPad),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.weekCellGap),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.weekCellGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isToday) {
                    Spacer(
                        modifier = Modifier
                            .size(AppSizes.dot)
                            .background(MaterialTheme.colorScheme.primary, AppShapes.dayMarker),
                    )
                }
                Text(
                    text = dayLabel,
                    style = WeekColumnTypography.dayLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = date.format(weekColDateFormatter),
                    style = WeekColumnTypography.date,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (mark == DayMark.HOLIDAY) {
                    HolidayMarker()
                }
            }
            visible.forEach { occurrence ->
                WeekEventCell(
                    occurrence = occurrence,
                    onClick = { onEventClick(occurrence.event.id) },
                )
            }
            if (extra > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = AppSizes.minTouch)
                        .clickable { onOpenDay(date) },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(R.string.week_more_events, extra),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Компактная строка события: полоса провайдера, время начала и название в одну линию.
 * Своего фона и рамки у строки нет — контур принадлежит карточке дня.
 */
@Composable
fun WeekEventCell(
    occurrence: EventOccurrence,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AppSizes.minTouch)
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.weekCellGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxHeight()
                .width(AppSpacing.providerStripe)
                .background(providerColor(occurrence.event.calendarProvider)),
        )
        Text(
            text = timeText(occurrence.startMillis),
            style = WeekColumnTypography.cellTime,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = occurrence.event.title,
            style = WeekColumnTypography.cellTitle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
