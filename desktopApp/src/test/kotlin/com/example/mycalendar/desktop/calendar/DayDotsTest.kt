package com.example.mycalendar.desktop.calendar

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DayDotsTest {

    private val zone = ZoneOffset.UTC
    private val from = LocalDate.of(2026, 11, 1)
    private val to = LocalDate.of(2026, 11, 30)

    private fun millis(dateTime: LocalDateTime): Long =
        dateTime.toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun event(
        id: String,
        start: LocalDateTime,
        end: LocalDateTime,
        provider: CalendarProvider = CalendarProvider.LOCAL,
        repeatRule: RepeatRule? = null,
    ) = Event(
        id = id,
        title = id,
        startTime = millis(start),
        endTime = millis(end),
        repeatRule = repeatRule,
        calendarProvider = provider,
    )

    @Test
    fun `single event marks its own day only`() {
        val dots = eventsByDay(
            listOf(event("a", LocalDateTime.of(2026, 11, 10, 10, 0), LocalDateTime.of(2026, 11, 10, 11, 0))),
            from,
            to,
            zone,
        )

        assertEquals(setOf(LocalDate.of(2026, 11, 10)), dots.keys)
        assertEquals(listOf(CalendarProvider.LOCAL), dots.getValue(LocalDate.of(2026, 11, 10)))
    }

    @Test
    fun `event across midnight marks both days`() {
        val dots = eventsByDay(
            listOf(event("a", LocalDateTime.of(2026, 11, 10, 23, 0), LocalDateTime.of(2026, 11, 11, 1, 0))),
            from,
            to,
            zone,
        )

        assertEquals(setOf(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 11)), dots.keys)
    }

    @Test
    fun `event ending exactly at midnight does not mark the next day`() {
        val dots = eventsByDay(
            listOf(event("a", LocalDateTime.of(2026, 11, 10, 22, 0), LocalDateTime.of(2026, 11, 11, 0, 0))),
            from,
            to,
            zone,
        )

        assertEquals(setOf(LocalDate.of(2026, 11, 10)), dots.keys)
    }

    @Test
    fun `weekly event marks every occurrence inside the range`() {
        val dots = eventsByDay(
            listOf(
                event(
                    "weekly",
                    LocalDateTime.of(2026, 10, 26, 9, 0),
                    LocalDateTime.of(2026, 10, 26, 10, 0),
                    repeatRule = RepeatRule.WEEKLY,
                ),
            ),
            from,
            to,
            zone,
        )

        assertEquals(
            setOf(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 16),
                LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 30)),
            dots.keys,
        )
    }

    @Test
    fun `same day events are ordered by start and keep providers`() {
        val dots = eventsByDay(
            listOf(
                event(
                    "late",
                    LocalDateTime.of(2026, 11, 10, 15, 0),
                    LocalDateTime.of(2026, 11, 10, 16, 0),
                    provider = CalendarProvider.YANDEX,
                ),
                event(
                    "early",
                    LocalDateTime.of(2026, 11, 10, 9, 0),
                    LocalDateTime.of(2026, 11, 10, 10, 0),
                    provider = CalendarProvider.GOOGLE,
                ),
            ),
            from,
            to,
            zone,
        )

        assertEquals(
            listOf(CalendarProvider.GOOGLE, CalendarProvider.YANDEX),
            dots.getValue(LocalDate.of(2026, 11, 10)),
        )
    }

    @Test
    fun `multi-day event started before range is clipped to the range`() {
        val dots = eventsByDay(
            listOf(event("long", LocalDateTime.of(2026, 10, 28, 12, 0), LocalDateTime.of(2026, 11, 2, 12, 0))),
            from,
            to,
            zone,
        )

        assertEquals(setOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)), dots.keys)
    }

    @Test
    fun `event outside the range is ignored`() {
        val dots = eventsByDay(
            listOf(event("a", LocalDateTime.of(2026, 12, 10, 10, 0), LocalDateTime.of(2026, 12, 10, 11, 0))),
            from,
            to,
            zone,
        )

        assertTrue(dots.isEmpty())
    }
}
