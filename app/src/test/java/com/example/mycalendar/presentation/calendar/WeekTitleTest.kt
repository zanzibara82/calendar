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

    @Test
    fun `week title names both months when the week crosses a month`() {
        // «26 – 1 ноября» читается как ноябрь–декабрь, поэтому месяц нужен у обеих границ.
        assertEquals("26 октября – 1 ноября 2026", weekTitle(LocalDate.of(2026, 10, 26)))
    }

    @Test
    fun `week title names both years when the week crosses a year`() {
        assertEquals("28 декабря – 3 января 2027", weekTitle(LocalDate.of(2026, 12, 28)))
    }
}
