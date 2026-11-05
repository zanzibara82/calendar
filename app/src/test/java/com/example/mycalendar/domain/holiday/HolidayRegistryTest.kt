package com.example.mycalendar.domain.holiday

import com.example.mycalendar.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Таблица твёрдых дат и приоритет меток. Год в ключе не участвует, поэтому високосное
 * 29 февраля обязано вести себя как обычный день.
 */
class HolidayRegistryTest {

    private val registry = HolidayRegistry()

    @Test
    fun `holiday beats weekend on the same date`() {
        // 9 мая 2026 — суббота: тон один, и это тон праздника
        assertEquals(DayMark.HOLIDAY, registry.markFor(LocalDate.of(2026, 5, 9)))
        assertEquals(R.string.holiday_victory_day, registry.holidayTitleRes(LocalDate.of(2026, 5, 9)))
    }

    @Test
    fun `weekend days are marked`() {
        // 7 и 8 ноября 2026 — суббота и воскресенье без праздника
        assertEquals(DayMark.WEEKEND, registry.markFor(LocalDate.of(2026, 11, 7)))
        assertEquals(DayMark.WEEKEND, registry.markFor(LocalDate.of(2026, 11, 8)))
        assertNull(registry.holidayTitleRes(LocalDate.of(2026, 11, 7)))
    }

    @Test
    fun `plain weekday has no mark`() {
        // 15 сентября 2026 — вторник вне таблицы
        assertNull(registry.markFor(LocalDate.of(2026, 9, 15)))
    }

    @Test
    fun `every fixed holiday date is in the table`() {
        val dates = listOf(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 6), LocalDate.of(2026, 1, 7),
            LocalDate.of(2026, 2, 14), LocalDate.of(2026, 2, 23), LocalDate.of(2026, 3, 8),
            LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 9), LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 12), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 4),
            LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 31),
        )
        dates.forEach { date ->
            assertEquals("нет метки у $date", DayMark.HOLIDAY, registry.markFor(date))
            assertNotNull("нет названия у $date", registry.holidayTitleRes(date))
        }
    }

    @Test
    fun `leap day is not a holiday and does not throw`() {
        // 29 февраля 2028 — вторник; в таблице его нет, и поиск по MonthDay безопасен
        assertNull(registry.markFor(LocalDate.of(2028, 2, 29)))
    }

    @Test
    fun `marksBetween covers the whole month`() {
        val marks = registry.marksBetween(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31))
        assertEquals(DayMark.HOLIDAY, marks[LocalDate.of(2026, 12, 25)])
        assertEquals(DayMark.HOLIDAY, marks[LocalDate.of(2026, 12, 31)])
        assertEquals(DayMark.WEEKEND, marks[LocalDate.of(2026, 12, 5)])
        assertNull(marks[LocalDate.of(2026, 12, 3)])
        assertEquals("в карте только помеченные дни", 10, marks.size)
    }
}
