package com.example.mycalendar.presentation.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Шапка недели — фраза, а не заголовок-существительное: месяц в ней стоит
 * в родительном падеже. Именительный («9 – 15 Ноябрь») читается как ошибка.
 */
class WeekTitleTest {

    @Test
    fun `week title uses genitive month`() {
        assertEquals("9 – 15 ноября 2026", weekTitle(LocalDate.of(2026, 11, 9)))
    }

    @Test
    fun `week title builds the range from the end date`() {
        // Неделя 2–8 марта 2026: обе границы в одном месяце, регистр месяца — строчный.
        assertEquals("2 – 8 марта 2026", weekTitle(LocalDate.of(2026, 3, 2)))
    }
}
