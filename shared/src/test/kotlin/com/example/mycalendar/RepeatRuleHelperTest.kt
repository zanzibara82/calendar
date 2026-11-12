package com.example.mycalendar

import com.example.mycalendar.domain.model.RepeatRule
import com.example.mycalendar.domain.util.RepeatRuleHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Тесты расчёта вхождений повторяющихся событий.
 */
class RepeatRuleHelperTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private fun millis(dateTime: LocalDateTime): Long =
        dateTime.atZone(zone).toInstant().toEpochMilli()

    private fun fromLocal(date: LocalDate, time: LocalTime = LocalTime.NOON): Long =
        millis(LocalDateTime.of(date, time))

    @Test
    fun `single event has no next occurrence`() {
        assertNull(RepeatRuleHelper.nextOccurrenceAfter(1_000L, null, 500L))
    }

    @Test
    fun `daily rule steps by one day`() {
        val start = fromLocal(LocalDate.of(2026, 9, 1))
        val after = fromLocal(LocalDate.of(2026, 9, 1))
        val next = RepeatRuleHelper.nextOccurrenceAfter(start, RepeatRule.DAILY, after)

        val expected = fromLocal(LocalDate.of(2026, 9, 2))
        assertEquals(expected, next)
    }

    @Test
    fun `occurrences in range for weekly rule`() {
        // Понедельник 31.08.2026
        val start = fromLocal(LocalDate.of(2026, 8, 31))
        val from = fromLocal(LocalDate.of(2026, 9, 1))
        val to = fromLocal(LocalDate.of(2026, 9, 30))

        val occurrences = RepeatRuleHelper.occurrencesInRange(
            originalStart = start,
            originalEnd = start + 3_600_000,
            rule = RepeatRule.WEEKLY,
            from = from,
            to = to,
        )
        // Понедельники сентября 2026: 7, 14, 21, 28
        assertEquals(4, occurrences.size)
    }

    @Test
    fun `month end clamps to shorter month`() {
        // 31 января -> следующее вхождение: 28 февраля 2027 (2027 не високосный)
        val start = fromLocal(LocalDate.of(2027, 1, 31))
        val next = RepeatRuleHelper.nextOccurrenceAfter(
            start,
            RepeatRule.MONTHLY,
            fromLocal(LocalDate.of(2027, 1, 31)),
        )
        val nextDate = Instant.ofEpochMilli(next!!).atZone(zone).toLocalDate()
        assertEquals(LocalDate.of(2027, 2, 28), nextDate)
    }
}
