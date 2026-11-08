package com.example.mycalendar.domain.holiday

import com.example.mycalendar.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.MonthDay

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
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 3),
            LocalDate.of(2026, 1, 4), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 6),
            LocalDate.of(2026, 1, 7),
            LocalDate.of(2026, 2, 14), LocalDate.of(2026, 2, 23), LocalDate.of(2026, 3, 8),
            LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 9), LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 12), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 4),
            LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 31),
        )
        dates.forEach { date ->
            assertEquals("нет метки у $date", DayMark.HOLIDAY, registry.markFor(date))
            assertNotNull("нет названия у $date", registry.holidayTitleRes(date))
        }
        // Лист закрывает все ключи таблицы: 18 различных пар (месяц, день). Раньше в листе
        // не было 2, 3, 4 и 5 января: они дают тот же ресурс, что и 1 января, но это
        // самостоятельные ключи таблицы, и каждый обязан быть затронут.
        assertEquals(
            "листы дат и ключей таблицы разошлись",
            18,
            dates.map { MonthDay.from(it) }.distinct().size,
        )
        // Эта же проверка краснеет, если из таблицы удалить строку: в невисокосном году
        // праздничных записей ровно столько же, сколько ключей.
        val holidayMarksOf2026 = registry
            .marksBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
            .count { entry -> entry.value == DayMark.HOLIDAY }
        assertEquals("в таблице стало не 18 ключей", 18, holidayMarksOf2026)
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

    /**
     * Левая граница диапазона. Проверка за месяц её не ловит: 1 декабря 2026 — вторник без
     * метки, и потерянная первая дата отрезка осталась бы незамеченной. Здесь первая дата
     * помечена, поэтому её тон обязан лежать в карте.
     */
    @Test
    fun `marksBetween covers the first day of the range`() {
        // 28 ноября 2026 и 5 декабря 2026 — субботы: обе границы помечены как выходной.
        val marks = registry.marksBetween(LocalDate.of(2026, 11, 28), LocalDate.of(2026, 12, 5))
        assertEquals("первая дата диапазона пропала", DayMark.WEEKEND, marks[LocalDate.of(2026, 11, 28)])
        assertEquals(DayMark.WEEKEND, marks[LocalDate.of(2026, 12, 5)])
        assertNull(marks[LocalDate.of(2026, 11, 30)])
        // Помеченные дни отрезка: 28.11 (сб), 29.11 (вс), 05.12 (сб).
        assertEquals("в карте только помеченные дни", 3, marks.size)
    }
}
