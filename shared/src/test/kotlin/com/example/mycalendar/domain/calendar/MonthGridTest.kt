package com.example.mycalendar.domain.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Форма сетки месяца: старт с понедельника, клеток кратно семи, весь месяц на месте.
 * По числу клеток клиенты считают диапазон меток дня, поэтому ошибка здесь ломает тон.
 */
class MonthGridTest {

    @Test
    fun `grid starts on monday of the week containing the first day`() {
        // 1 ноября 2026 — воскресенье: сетка обязана начаться 26 октября
        val weeks = monthWeeks(LocalDate.of(2026, 11, 1))
        assertEquals(LocalDate.of(2026, 10, 26), weeks.first().first())
        assertEquals(LocalDate.of(2026, 12, 6), weeks.last().last())
        assertEquals(6, weeks.size)
    }

    @Test
    fun `month starting on monday takes exactly four weeks`() {
        // 1 февраля 2027 — понедельник, 28 дней
        val weeks = monthWeeks(LocalDate.of(2027, 2, 1))
        assertEquals(4, weeks.size)
        assertEquals(LocalDate.of(2027, 2, 1), weeks.first().first())
        assertEquals(LocalDate.of(2027, 2, 28), weeks.last().last())
    }

    @Test
    fun `every week has seven consecutive days`() {
        val weeks = monthWeeks(LocalDate.of(2026, 8, 15))
        assertTrue(weeks.all { it.size == 7 })
        val flat = weeks.flatten()
        flat.zipWithNext().forEach { (a, b) -> assertEquals(a.plusDays(1), b) }
    }

    @Test
    fun `all days of the month are covered`() {
        val month = LocalDate.of(2026, 3, 1)
        val flat = monthWeeks(month).flatten()
        (1..month.lengthOfMonth()).forEach { day ->
            assertTrue("нет дня $day", flat.contains(LocalDate.of(2026, 3, day)))
        }
    }
}
