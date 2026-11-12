package com.example.mycalendar.presentation.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mycalendar.R
import com.example.mycalendar.domain.calendar.monthWeeks
import com.example.mycalendar.domain.holiday.DayMark
import com.example.mycalendar.domain.holiday.HolidayKey
import com.example.mycalendar.domain.holiday.HolidayRegistry
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.repository.SettingsRepository
import com.example.mycalendar.domain.usecase.ExpandOccurrencesUseCase
import com.example.mycalendar.domain.usecase.ObserveEventsBetweenUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    /** Метки дней видимого диапазона: тон клетки, карточки и шапки. Пусто — без тона. */
    val dayMarks: Map<LocalDate, DayMark> = emptyMap(),
    /** Ресурсы названий праздников по тем же датам; заполнены только для DayMark.HOLIDAY. */
    val holidayTitles: Map<LocalDate, Int> = emptyMap(),
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

    /**
     * Диапазон дней, которые показывает сетка. Не путать с [rangeMillis]: тот задаёт,
     * что грузить из Room, и у месяца ограничен календарным месяцем, а клеток сетки —
     * до сорока двух, и тон обязаны получить тоже дни соседних месяцев.
     */
    fun gridBounds(): Pair<LocalDate, LocalDate> = when (viewMode) {
        CalendarViewMode.MONTH -> {
            val weeks = monthWeeks(visibleMonth)
            val start = weeks.first().first()
            val end = weeks.last().last()
            // Выбранный день мог остаться в другом месяце: шапка под сеткой всё равно
            // обязана получить свой тон, поэтому диапазон накрывает и его.
            minOf(start, selectedDate) to maxOf(end, selectedDate)
        }

        CalendarViewMode.WEEK -> {
            val monday = selectedDate.with(DayOfWeek.MONDAY)
            monday to monday.plusDays(6)
        }

        CalendarViewMode.DAY -> selectedDate to selectedDate
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
    private val holidayRegistry: HolidayRegistry,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())

    /**
     * Состояние с метками дня. Считается map-ом по `_uiState`: вход здесь только даты
     * видимого диапазона, второго потока нет. Eagerly — чтобы значение было готово до
     * первой подписки. Обычный объём — клетки видимой сетки (до сорока двух); когда выбранный
     * день остался в другом месяце, диапазон накрывает и его, и карта растёт до этого разрыва.
     */
    val uiState: StateFlow<CalendarUiState> = _uiState
        .map { state ->
            val (from, to) = state.gridBounds()
            val marks = holidayRegistry.marksBetween(from, to)
            val titles = marks
                .filterValues { it == DayMark.HOLIDAY }
                .mapNotNull { (date, _) -> holidayRegistry.holidayKey(date)?.let { date to it.titleRes() } }
                .toMap()
            state.copy(dayMarks = marks, holidayTitles = titles)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CalendarUiState())

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

    /** Тихий режим для индикатора в шапке: показывается только при активном режиме. */
    val quietMode: StateFlow<Boolean> = settingsRepository.quietMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Тап по индикатору выключает режим; включается он только из настроек. */
    fun setQuietMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setQuietMode(enabled) }
    }

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

/** Подпись праздника: домен отдаёт [HolidayKey], текст — ресурс Android-клиента. */
private fun HolidayKey.titleRes(): Int = when (this) {
    HolidayKey.NEW_YEAR_HOLIDAYS -> R.string.holiday_new_year_holidays
    HolidayKey.CHRISTMAS_ORTHODOX -> R.string.holiday_christmas_orthodox
    HolidayKey.VALENTINES -> R.string.holiday_valentines
    HolidayKey.DEFENDER_OF_FATHERLAND -> R.string.holiday_defender_of_fatherland
    HolidayKey.WOMENS_DAY -> R.string.holiday_womens_day
    HolidayKey.SPRING_LABOR -> R.string.holiday_spring_labor
    HolidayKey.VICTORY_DAY -> R.string.holiday_victory_day
    HolidayKey.CHILDRENS_DAY -> R.string.holiday_childrens_day
    HolidayKey.RUSSIA_DAY -> R.string.holiday_russia_day
    HolidayKey.HALLOWEEN -> R.string.holiday_halloween
    HolidayKey.NATIONAL_UNITY -> R.string.holiday_national_unity
    HolidayKey.CHRISTMAS_WEST -> R.string.holiday_christmas_west
    HolidayKey.NEW_YEAR_EVE -> R.string.holiday_new_year_eve
}
