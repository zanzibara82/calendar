package com.example.mycalendar.desktop.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mycalendar.desktop.theme.EventCardGradient
import com.example.mycalendar.desktop.theme.LocalDesktopColors
import com.example.mycalendar.desktop.theme.dayContainer
import com.example.mycalendar.domain.calendar.monthWeeks
import com.example.mycalendar.domain.holiday.DayMark
import com.example.mycalendar.domain.holiday.HolidayKey
import com.example.mycalendar.domain.holiday.HolidayRegistry
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import com.example.mycalendar.domain.repository.EventRepository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val RU = Locale.forLanguageTag("ru")
private val DATE_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/** Как на Android: больше трёх точек клетка не вмещает. */
private const val MAX_DAY_DOTS = 3

/** Высота списка событий дня: прокрутка вместо того, чтобы отнимать сетку целиком. */
private val DAY_EVENTS_MAX_HEIGHT = 180.dp

/** Месячный вид: сетка Пн–Вс, тон выходного и праздника из общего домена, точки событий. */
@Composable
fun MonthView(
    repository: EventRepository,
    initialMonth: YearMonth = YearMonth.now(),
    infoEvent: Event? = null,
    onOpenSettings: () -> Unit = {},
    onAddEvent: (LocalDate) -> Unit = {},
    onEventClick: (Event) -> Unit = {},
    onCloseInfo: () -> Unit = {},
    onEditEvent: (Event) -> Unit = {},
    onDeleteEvent: (Event) -> Unit = {},
) {
    var month by remember { mutableStateOf(initialMonth) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    MonthView(
        repository = repository,
        month = month,
        selectedDate = selected,
        onMonthChange = { month = it },
        onSelectDate = { selected = it },
        infoEvent = infoEvent,
        onOpenSettings = onOpenSettings,
        onAddEvent = onAddEvent,
        onEventClick = onEventClick,
        onCloseInfo = onCloseInfo,
        onEditEvent = onEditEvent,
        onDeleteEvent = onDeleteEvent,
    )
}

/**
 * Та же сетка с состоянием снаружи: экран настроек заменяет календарь в окне,
 * и месяц с выбранным днём хранит вызывающий, чтобы возврат ничего не сбрасывал.
 */
@Composable
fun MonthView(
    repository: EventRepository,
    month: YearMonth,
    selectedDate: LocalDate,
    onMonthChange: (YearMonth) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    infoEvent: Event? = null,
    onOpenSettings: () -> Unit = {},
    onAddEvent: (LocalDate) -> Unit = {},
    onEventClick: (Event) -> Unit = {},
    onCloseInfo: () -> Unit = {},
    onEditEvent: (Event) -> Unit = {},
    onDeleteEvent: (Event) -> Unit = {},
) {
    val registry = remember { HolidayRegistry() }
    val zone = remember { ZoneId.systemDefault() }

    val weeks = remember(month) { monthWeeks(month.atDay(1)) }
    val marks = remember(weeks) { registry.marksBetween(weeks.first().first(), weeks.last().last()) }

    val days = remember(weeks) { weeks.flatten() }
    val events by produceState(initialValue = emptyList<Event>(), repository, weeks) {
        val from = days.first().atStartOfDay(zone).toInstant().toEpochMilli()
        val to = days.last().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        repository.observeEventsBetween(from, to).collect { value = it }
    }
    val dots = remember(events, days) { eventsByDay(events, days.first(), days.last(), zone) }
    val dayEvents = remember(events, selectedDate) { dayOccurrences(events, selectedDate, zone) }
    // Панель показывает свежую версию события: после правки в хранилище приходит новый снимок.
    val shownInfo = infoEvent?.let { target -> events.firstOrNull { it.id == target.id } ?: target }
    var pendingDelete by remember { mutableStateOf<Event?>(null) }

    Box(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Header(
                    month = month,
                    onPrevious = { onMonthChange(month.minusMonths(1)) },
                    onNext = { onMonthChange(month.plusMonths(1)) },
                    onToday = {
                        val today = LocalDate.now()
                        onMonthChange(YearMonth.from(today))
                        onSelectDate(today)
                    },
                    onOpenSettings = onOpenSettings,
                )
                WeekdayHeader()
                weeks.forEach { week ->
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        week.forEach { date ->
                            DayCell(
                                date = date,
                                inMonth = YearMonth.from(date) == month,
                                mark = marks[date],
                                isToday = date == LocalDate.now(),
                                isSelected = date == selectedDate,
                                dots = dots[date].orEmpty(),
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onClick = { onSelectDate(date) },
                            )
                        }
                    }
                }
                DayPanel(
                    date = selectedDate,
                    holidayKey = registry.holidayKey(selectedDate),
                    events = dayEvents,
                    zone = zone,
                    onAddEvent = { onAddEvent(selectedDate) },
                    onEventClick = onEventClick,
                )
            }
            if (shownInfo != null) {
                EventInfoPanel(
                    event = shownInfo,
                    date = selectedDate,
                    zone = zone,
                    onEdit = onEditEvent,
                    onDelete = { pendingDelete = it },
                    onClose = onCloseInfo,
                )
            }
        }
        pendingDelete?.let { event ->
            ConfirmDeleteDialog(
                title = event.title,
                onCancel = { pendingDelete = null },
                onConfirm = {
                    pendingDelete = null
                    onDeleteEvent(event)
                },
            )
        }
    }
}

@Composable
private fun Header(
    month: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = monthTitle(month),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onPrevious) { Text("‹") }
        TextButton(onClick = onToday) { Text("Сегодня") }
        TextButton(onClick = onNext) { Text("›") }
        IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("open-settings")) {
            Icon(Icons.Filled.Settings, contentDescription = "Настройки")
        }
    }
}

@Composable
private fun WeekdayHeader() {
    val colors = LocalDesktopColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DayOfWeek.entries.sortedBy { it.value }.forEach { day ->
            Text(
                text = capitalize(day.getDisplayName(TextStyle.SHORT_STANDALONE, RU)),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    mark: DayMark?,
    isToday: Boolean,
    isSelected: Boolean,
    dots: List<CalendarProvider>,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalDesktopColors.current
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val borderColor = when {
        isSelected -> colors.primary
        isToday -> colors.outline
        else -> colors.dayBorder
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(dayContainer(mark))
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("day-$date")
            .padding(8.dp),
    ) {
        Column {
            Text(
                text = date.dayOfMonth.toString(),
                color = if (inMonth) colors.onSurface else colors.onSurfaceVariant,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                dots.take(MAX_DAY_DOTS).forEachIndexed { index, provider ->
                    ProviderDot(provider, Modifier.testTag("event-dot-$date-$index"))
                }
            }
        }
        if (mark == DayMark.HOLIDAY) {
            // Форма-маркер: по одному тону праздник не отличить от выходного (WCAG 1.4.1).
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(colors.primary)
                    .testTag("holiday-marker-$date"),
            )
        }
    }
}

@Composable
private fun DayPanel(
    date: LocalDate,
    holidayKey: HolidayKey?,
    events: List<DayOccurrence>,
    zone: ZoneId,
    onAddEvent: () -> Unit,
    onEventClick: (Event) -> Unit,
) {
    val colors = LocalDesktopColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val title = holidayKey?.let(::holidayTitle)
            Text(
                text = if (title != null) "${dayTitle(date)} — $title" else dayTitle(date),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            LegendItem(colors.weekend, "Выходной")
            LegendItem(colors.holiday, "Праздник")
            IconButton(onClick = onAddEvent, modifier = Modifier.testTag("add-event")) {
                Icon(Icons.Filled.Add, contentDescription = "Добавить событие")
            }
        }
        if (events.isEmpty()) {
            Text(
                text = "Событий нет",
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("day-events-empty"),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = DAY_EVENTS_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .testTag("day-events"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                events.forEach {
                    DayEventRow(
                        occurrence = it,
                        date = date,
                        zone = zone,
                        onClick = { onEventClick(it.event) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DayEventRow(occurrence: DayOccurrence, date: LocalDate, zone: ZoneId, onClick: () -> Unit) {
    val event = occurrence.event
    val colors = LocalDesktopColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.card)
            .clickable(onClick = onClick)
            .testTag("day-event-${event.id}"),
    ) {
        // Шапка карточки: фирменный градиент бирюза → синий → тёмно-синий.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Brush.horizontalGradient(EventCardGradient)),
        )
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = event.title,
                    color = colors.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle(occurrence, date, zone, event.repeatRule),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Время вхождения в дне; граница, выходящая за день, показывается многоточием. */
private fun subtitle(occurrence: DayOccurrence, date: LocalDate, zone: ZoneId, rule: RepeatRule?): String {
    val time = timeRange(occurrence.start, occurrence.end, date, zone)
    val repeat = when (rule) {
        RepeatRule.DAILY -> "ежедневно"
        RepeatRule.WEEKLY -> "еженедельно"
        RepeatRule.MONTHLY -> "ежемесячно"
        RepeatRule.YEARLY -> "ежегодно"
        null -> null
    }
    return if (repeat != null) "$time · $repeat" else time
}

/** Границы дня — полуинтервал; открытый край (начался раньше или кончается позже) — «…». */
internal fun timeRange(start: Long, end: Long, date: LocalDate, zone: ZoneId): String {
    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val startText = if (start < dayStart) "…" else formatTime(start, zone)
    val endText = if (end > dayEnd) "…" else formatTime(end, zone)
    return "$startText–$endText"
}

private fun formatTime(millis: Long, zone: ZoneId): String =
    TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone))

/** «Среда, 11 ноября 2026»: в панели дня мало одного числа. */
internal fun dayTitle(date: LocalDate): String =
    capitalize(date.dayOfWeek.getDisplayName(TextStyle.FULL_STANDALONE, RU)) + ", " + date.format(DATE_FORMAT)

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, color = LocalDesktopColors.current.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}

private fun monthTitle(month: YearMonth): String =
    capitalize(month.month.getDisplayName(TextStyle.FULL_STANDALONE, RU)) + " " + month.year

private fun capitalize(text: String): String =
    text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
