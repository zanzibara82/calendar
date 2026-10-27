package com.example.mycalendar.presentation.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.mycalendar.R
import com.example.mycalendar.presentation.common.dayTitleFormatter
import com.example.mycalendar.presentation.common.monthTitleFormatter
import com.example.mycalendar.presentation.common.russianLocale
import com.example.mycalendar.presentation.common.weekdayTitleFormatter
import com.example.mycalendar.presentation.components.EmptyState
import com.example.mycalendar.presentation.components.EventListItem
import com.example.mycalendar.presentation.components.EventOccurrence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields

/**
 * Главный экран календаря: виды Месяц/Неделя/День, список событий, FAB создания.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onCreateEvent: () -> Unit,
    onEventClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val occurrences by viewModel.occurrences.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = headerTitle(state)) },
                actions = {
                    IconButton(onClick = viewModel::goToday) {
                        Icon(Icons.Filled.Today, contentDescription = stringResource(R.string.today))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateEvent,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.new_event)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Переключатель видов: Месяц / Неделя / День
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val modes = listOf(
                    CalendarViewMode.MONTH to stringResource(R.string.view_month),
                    CalendarViewMode.WEEK to stringResource(R.string.view_week),
                    CalendarViewMode.DAY to stringResource(R.string.view_day),
                )
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = state.viewMode == mode,
                        onClick = { viewModel.setViewMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    ) { Text(label) }
                }
            }

            // Стрелки периодов
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::goPrevious) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
                Spacer(modifier = Modifier.width(24.dp))
                IconButton(onClick = viewModel::goNext) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }

            when (state.viewMode) {
                CalendarViewMode.MONTH -> MonthView(
                    state = state,
                    occurrences = occurrences,
                    onDayClick = viewModel::selectDate,
                    onEventClick = onEventClick,
                    eventsOn = viewModel::eventsOn,
                )

                CalendarViewMode.WEEK -> WeekView(
                    state = state,
                    occurrences = occurrences,
                    onEventClick = onEventClick,
                    eventsOn = viewModel::eventsOn,
                )

                CalendarViewMode.DAY -> DayView(
                    state = state,
                    occurrences = occurrences,
                    onEventClick = onEventClick,
                    eventsOn = viewModel::eventsOn,
                )
            }
        }
    }
}

// ---------- Вид «Месяц» ----------

@Composable
private fun MonthView(
    state: CalendarUiState,
    occurrences: List<EventOccurrence>,
    onDayClick: (LocalDate) -> Unit,
    onEventClick: (String) -> Unit,
    eventsOn: (LocalDate, List<EventOccurrence>) -> List<EventOccurrence>,
) {
    val weeks = remember(state.visibleMonth) { monthWeeks(state.visibleMonth) }
    val selectedDayEvents = remember(state.selectedDate, occurrences) {
        eventsOn(state.selectedDate, occurrences)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Заголовки дней недели: трёхбуквенные сокращения (пн вт ср чт пт сб вс)
        Row(modifier = Modifier.fillMaxWidth()) {
            for (dayOfWeek in DayOfWeek.entries) {
                Text(
                    text = dayShortName(dayOfWeek),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Сетка дней месяца
        weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    val inMonth = date.month == state.visibleMonth.month
                    val isSelected = date == state.selectedDate
                    val isToday = date == LocalDate.now()
                    val dayEvents = eventsOn(date, occurrences)

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(2.dp)
                            .background(
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    isToday -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surface
                                },
                                shape = RoundedCornerShape(8.dp),
                            )
                            .clickable { onDayClick(date) }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = date.dayOfMonth.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                isSelected -> MaterialTheme.colorScheme.onPrimary
                                !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                        // Точки событий (максимум 3)
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            dayEvents.take(3).forEach { occurrence ->
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .background(
                                            com.example.mycalendar.presentation.components
                                                .providerColor(occurrence.event.calendarProvider),
                                            CircleShape,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }

        // События выбранного дня
        Text(
            text = fullDateTitle(state.selectedDate),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        EventList(events = selectedDayEvents, onEventClick = onEventClick)
    }
}

// ---------- Вид «Неделя» ----------

@Composable
private fun WeekView(
    state: CalendarUiState,
    occurrences: List<EventOccurrence>,
    onEventClick: (String) -> Unit,
    eventsOn: (LocalDate, List<EventOccurrence>) -> List<EventOccurrence>,
) {
    val weekStart = state.selectedDate.with(DayOfWeek.MONDAY)
    val days = remember(weekStart) { (0..6).map { weekStart.plusDays(it.toLong()) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        days.forEach { date ->
            val dayEvents = eventsOn(date, occurrences)
            item(key = "header_$date") {
                Text(
                    text = shortDateTitle(date),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (date == LocalDate.now()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (dayEvents.isEmpty()) {
                item(key = "empty_$date") {
                    Text(
                        text = stringResource(R.string.no_events),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(dayEvents, key = { "${it.event.id}_${it.startMillis}" }) { occurrence ->
                    EventListItem(occurrence = occurrence, onClick = { onEventClick(occurrence.event.id) })
                }
            }
        }
    }
}

// ---------- Вид «День» ----------

@Composable
private fun DayView(
    state: CalendarUiState,
    occurrences: List<EventOccurrence>,
    onEventClick: (String) -> Unit,
    eventsOn: (LocalDate, List<EventOccurrence>) -> List<EventOccurrence>,
) {
    val dayEvents = remember(state.selectedDate, occurrences) { eventsOn(state.selectedDate, occurrences) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = fullDateTitle(state.selectedDate),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        if (dayEvents.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.no_events),
                    subtitle = stringResource(R.string.no_events_hint),
                )
            }
        } else {
            items(dayEvents, key = { "${it.event.id}_${it.startMillis}" }) { occurrence ->
                EventListItem(occurrence = occurrence, onClick = { onEventClick(occurrence.event.id) })
            }
        }
    }
}

// ---------- Вспомогательные ----------

@Composable
private fun EventList(events: List<EventOccurrence>, onEventClick: (String) -> Unit) {
    if (events.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.no_events),
            subtitle = stringResource(R.string.no_events_hint),
        )
    } else {
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(events, key = { "${it.event.id}_${it.startMillis}" }) { occurrence ->
                EventListItem(occurrence = occurrence, onClick = { onEventClick(occurrence.event.id) })
            }
        }
    }
}

/** Заголовок TopAppBar по режиму: «Сентябрь 2026» / «1 – 7 сентября» / «5 сентября, суббота». */
@Composable
private fun headerTitle(state: CalendarUiState): String = when (state.viewMode) {
    CalendarViewMode.MONTH -> monthTitle(state.visibleMonth)
    CalendarViewMode.WEEK -> {
        val start = state.selectedDate.with(DayOfWeek.MONDAY)
        "${start.dayOfMonth} – ${start.plusDays(6).dayOfMonth} ${monthTitle(start.plusDays(6))}"
    }
    CalendarViewMode.DAY -> fullDateTitle(state.selectedDate)
}

/** Сокращённые названия дней недели ровно по три буквы: пн..вс. */
private val dayShortNames = mapOf(
    DayOfWeek.MONDAY to "пн",
    DayOfWeek.TUESDAY to "вт",
    DayOfWeek.WEDNESDAY to "ср",
    DayOfWeek.THURSDAY to "чт",
    DayOfWeek.FRIDAY to "пт",
    DayOfWeek.SATURDAY to "сб",
    DayOfWeek.SUNDAY to "вс",
)

private fun dayShortName(dayOfWeek: DayOfWeek): String =
    dayShortNames.getValue(dayOfWeek)

private fun monthTitle(date: LocalDate): String =
    date.format(monthTitleFormatter).replaceFirstChar { it.uppercase(russianLocale) }

private fun shortDateTitle(date: LocalDate): String =
    date.format(dayTitleFormatter)

private fun fullDateTitle(date: LocalDate): String =
    date.format(weekdayTitleFormatter).replaceFirstChar { it.uppercase(russianLocale) }

/**
 * Сетка месяца: недели (Пн–Вс), включая дни соседних месяцев.
 */
private fun monthWeeks(month: LocalDate): List<List<LocalDate>> {
    val first = month.withDayOfMonth(1)
    val firstCell = first.with(DayOfWeek.MONDAY)
    val daysInMonth = month.lengthOfMonth()
    // Число клеток, кратное 7: захватываем хвост соседнего месяца
    val totalCells = ((first.dayOfWeek.value - 1) + daysInMonth)
        .let { used -> ((used + 6) / 7) * 7 }
    return (0 until totalCells).chunked(7).map { week ->
        week.map { firstCell.plusDays(it.toLong()) }
    }
}
