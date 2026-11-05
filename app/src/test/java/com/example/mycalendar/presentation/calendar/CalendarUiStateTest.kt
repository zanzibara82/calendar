package com.example.mycalendar.presentation.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Диапазон меток дня — это то, что реально показывает сетка, а не то, что загружено из
 * Room: у месяца шесть недель, и дни соседних месяцев тоже обязаны получить тон.
 */
class CalendarUiStateTest {

    @Test
    fun `month bounds cover the whole grid`() {
        val state = CalendarUiState(
            viewMode = CalendarViewMode.MONTH,
            selectedDate = LocalDate.of(2026, 11, 5),
            visibleMonth = LocalDate.of(2026, 11, 1),
        )
        // Ноябрь 2026 начинается в воскресенье: первая клетка — 26 октября, последняя — 6 декабря
        assertEquals(LocalDate.of(2026, 10, 26) to LocalDate.of(2026, 12, 6), state.gridBounds())
    }

    @Test
    fun `month bounds keep the selected day inside`() {
        val state = CalendarUiState(
            viewMode = CalendarViewMode.MONTH,
            selectedDate = LocalDate.of(2026, 12, 25),
            visibleMonth = LocalDate.of(2026, 11, 1),
        )
        // Выбор мог остаться в другом месяце: шапка дня под сеткой обязана попасть в диапазон
        assertEquals(LocalDate.of(2026, 10, 26) to LocalDate.of(2026, 12, 25), state.gridBounds())
    }

    @Test
    fun `week bounds are monday through sunday`() {
        val state = CalendarUiState(
            viewMode = CalendarViewMode.WEEK,
            selectedDate = LocalDate.of(2026, 11, 5),
        )
        assertEquals(LocalDate.of(2026, 11, 2) to LocalDate.of(2026, 11, 8), state.gridBounds())
    }

    @Test
    fun `day bounds are the single selected day`() {
        val state = CalendarUiState(
            viewMode = CalendarViewMode.DAY,
            selectedDate = LocalDate.of(2026, 5, 9),
        )
        assertEquals(LocalDate.of(2026, 5, 9) to LocalDate.of(2026, 5, 9), state.gridBounds())
    }
}
