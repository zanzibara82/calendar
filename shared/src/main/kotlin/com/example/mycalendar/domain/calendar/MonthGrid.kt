package com.example.mycalendar.domain.calendar

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Сетка месяца: недели Пн–Вс, включая дни соседних месяцев, которые попадают в первую
 * и последнюю неделю. Единственный источник числа клеток — от него зависит и диапазон
 * меток дня (`CalendarUiState.gridBounds` в Android-клиенте).
 *
 * Функция общая для клиентов: обе версии рисуют один и тот же месяц одинаково.
 */
fun monthWeeks(month: LocalDate): List<List<LocalDate>> {
    val first = month.withDayOfMonth(1)
    val firstCell = first.with(DayOfWeek.MONDAY)
    val daysInMonth = month.lengthOfMonth()
    // Число клеток, кратное 7: захватываем хвост соседнего месяца
    val totalCells = ((first.dayOfWeek.value - 1) + daysInMonth)
        .let { used -> ((used + 6) / 7) * 7 }
    return (0 until totalCells).chunked(7).map { week ->
        week.map { index -> firstCell.plusDays(index.toLong()) }
    }
}
