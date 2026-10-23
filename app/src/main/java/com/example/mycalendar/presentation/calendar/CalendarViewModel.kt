package com.example.mycalendar.presentation.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.usecase.ExpandOccurrencesUseCase
import com.example.mycalendar.domain.usecase.ObserveEventsBetweenUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Вид календаря по ТЗ: Месяц / Неделя / День. */
enum class CalendarViewMode { MONTH, WEEK, DAY }

data class CalendarUiState(
    val viewMode: CalendarViewMode = CalendarViewMode.MONTH,
    /** Выбранный день (для списка событий под сеткой и видов Week/Day). */
    val selectedDate: LocalDate = LocalDate.now(),
    /** Отображаемый месяц в виде Месяц. */
    val visibleMonth: LocalDate = LocalDate.now(),
) {
    /**
     * Диапазон (epoch millis) загружаемых событий:
     *  - Месяц: весь видимый месяц;
     *  - Неделя: Пн–Вс недели выбранного дня;
     *  - День: выбранный день.
     */
    fun rangeMillis(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        fun LocalDate.startMillis() = atStartOfDay(zone).toInstant().toEpochMilli()
        fun LocalDate.endMillis() = plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

        return when (viewMode) {
            CalendarViewMode.MONTH -> visibleMonth.withDayOfMonth(1).startMillis() to
                visibleMonth.withDayOfMonth(visibleMonth.lengthOfMonth()).endMillis()

            CalendarViewMode.WEEK -> selectedDate.with(DayOfWeek.MONDAY).startMillis() to
                selectedDate.with(DayOfWeek.SUNDAY).endMillis()

            CalendarViewMode.DAY -> selectedDate.startMillis() to selectedDate.endMillis()
        }
    }
}

/**
 * ViewModel главного экрана календаря.
 *
 * Ключевая логика: события из Room наблюдаются для диапазона текущего вида;
 * повторяющиеся события разворачиваются в конкретные вхождения через
 * ExpandOccurrencesUseCase (RepeatRuleHelper), поэтому "ежедневное" событие
 * появляется на каждом дне сетки.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    observeEventsBetween: ObserveEventsBetweenUseCase,
    private val expandOccurrences: ExpandOccurrencesUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    /** Развёрнутые вхождения событий для текущего вида календаря. */
    val occurrences: StateFlow<List<com.example.mycalendar.presentation.components.EventOccurrence>> =
        _uiState
            .flatMapLatest { state ->
                val (from, to) = state.rangeMillis()
                observeEventsBetween(from, to)
            }
            .map { events ->
                val state = _uiState.value
                val (from, to) = state.rangeMillis()
                expandOccurrences(events, from, to)
                    .map { (event: Event, range: Pair<Long, Long>) ->
                        com.example.mycalendar.presentation.components.EventOccurrence(
                            event = event,
                            startMillis = range.first,
                            endMillis = range.second,
                        )
                    }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setViewMode(mode: CalendarViewMode) = _uiState.update { it.copy(viewMode = mode) }

    fun selectDate(date: LocalDate) = _uiState.update { it.copy(selectedDate = date) }

    fun goToday() = _uiState.update {
        it.copy(selectedDate = LocalDate.now(), visibleMonth = LocalDate.now())
    }

    /** Переход к предыдущему периоду (месяц/неделя/день). */
    fun goPrevious() = _uiState.update { state ->
        when (state.viewMode) {
            CalendarViewMode.MONTH -> state.copy(visibleMonth = state.visibleMonth.minusMonths(1))
            CalendarViewMode.WEEK -> state.copy(selectedDate = state.selectedDate.minusWeeks(1))
            CalendarViewMode.DAY -> state.copy(selectedDate = state.selectedDate.minusDays(1))
        }
    }

    /** Переход к следующему периоду. */
    fun goNext() = _uiState.update { state ->
        when (state.viewMode) {
            CalendarViewMode.MONTH -> state.copy(visibleMonth = state.visibleMonth.plusMonths(1))
            CalendarViewMode.WEEK -> state.copy(selectedDate = state.selectedDate.plusWeeks(1))
            CalendarViewMode.DAY -> state.copy(selectedDate = state.selectedDate.plusDays(1))
        }
    }

    /** События конкретного дня (для точек в сетке месяца и списков). */
    fun eventsOn(date: LocalDate, all: List<com.example.mycalendar.presentation.components.EventOccurrence>): List<com.example.mycalendar.presentation.components.EventOccurrence> {
        val zone = ZoneId.systemDefault()
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return all.filter { it.startMillis <= to && it.endMillis >= from }
    }
}
