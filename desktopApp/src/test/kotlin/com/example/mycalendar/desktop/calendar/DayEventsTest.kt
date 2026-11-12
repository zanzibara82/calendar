package com.example.mycalendar.desktop.calendar

import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DayEventsTest {

    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 11, 10)

    private fun millis(dateTime: LocalDateTime): Long =
        dateTime.toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun event(
        id: String,
        start: LocalDateTime,
        end: LocalDateTime,
        repeatRule: RepeatRule? = null,
    ) = Event(
        id = id,
        title = id,
        startTime = millis(start),
        endTime = millis(end),
        repeatRule = repeatRule,
    )

    @Test
    fun `single event of the day is returned with its times`() {
        val occurrence = event(
            "a",
            LocalDateTime.of(2026, 11, 10, 10, 0),
            LocalDateTime.of(2026, 11, 10, 11, 30),
        )

        val events = dayOccurrences(listOf(occurrence), day, zone)

        assertEquals(1, events.size)
        assertEquals("a", events.single().event.id)
        assertEquals(millis(LocalDateTime.of(2026, 11, 10, 10, 0)), events.single().start)
        assertEquals(millis(LocalDateTime.of(2026, 11, 10, 11, 30)), events.single().end)
    }

    @Test
    fun `event ending exactly at midnight stays on its own day`() {
        val occurrence = event(
            "a",
            LocalDateTime.of(2026, 11, 10, 22, 0),
            LocalDateTime.of(2026, 11, 11, 0, 0),
        )

        assertEquals(1, dayOccurrences(listOf(occurrence), day, zone).size)
        assertTrue(dayOccurrences(listOf(occurrence), day.plusDays(1), zone).isEmpty())
    }

    @Test
    fun `long event keeps its real start on every day it touches`() {
        val occurrence = event(
            "a",
            LocalDateTime.of(2026, 11, 8, 18, 0),
            LocalDateTime.of(2026, 11, 12, 9, 0),
        )

        val middle = dayOccurrences(listOf(occurrence), day, zone).single()

        assertEquals(millis(LocalDateTime.of(2026, 11, 8, 18, 0)), middle.start)
        assertEquals(millis(LocalDateTime.of(2026, 11, 12, 9, 0)), middle.end)
    }

    @Test
    fun `weekly rule expands to the occurrence of the day`() {
        val occurrence = event(
            "a",
            LocalDateTime.of(2026, 11, 2, 9, 0),
            LocalDateTime.of(2026, 11, 2, 10, 0),
            repeatRule = RepeatRule.WEEKLY,
        )

        val events = dayOccurrences(listOf(occurrence), day.minusDays(1), zone)

        assertEquals(1, events.size)
        assertEquals(millis(LocalDateTime.of(2026, 11, 9, 9, 0)), events.single().start)
        assertEquals(millis(LocalDateTime.of(2026, 11, 9, 10, 0)), events.single().end)
    }

    @Test
    fun `event outside the day is ignored`() {
        val occurrence = event(
            "a",
            LocalDateTime.of(2026, 11, 12, 10, 0),
            LocalDateTime.of(2026, 11, 12, 11, 0),
        )

        assertTrue(dayOccurrences(listOf(occurrence), day, zone).isEmpty())
    }

    @Test
    fun `events are ordered by start time`() {
        val late = event(
            "late",
            LocalDateTime.of(2026, 11, 10, 18, 0),
            LocalDateTime.of(2026, 11, 10, 19, 0),
        )
        val early = event(
            "early",
            LocalDateTime.of(2026, 11, 10, 9, 0),
            LocalDateTime.of(2026, 11, 10, 10, 0),
        )

        val events = dayOccurrences(listOf(late, early), day, zone)

        assertEquals(listOf("early", "late"), events.map { it.event.id })
    }
}
