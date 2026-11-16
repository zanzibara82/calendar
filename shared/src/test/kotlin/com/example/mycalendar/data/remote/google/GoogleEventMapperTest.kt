package com.example.mycalendar.data.remote.google

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleEventMapperTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private fun timedDto() = GoogleEventDto(
        id = "event-1",
        status = "confirmed",
        summary = "Созвон",
        description = "Обсудить план",
        start = GoogleDateTimeDto(dateTime = "2026-05-09T10:00:00+03:00"),
        end = GoogleDateTimeDto(dateTime = "2026-05-09T11:30:00+03:00"),
        recurrence = listOf("RRULE:FREQ=WEEKLY;INTERVAL=2"),
        reminders = GoogleRemindersDto(
            useDefault = false,
            overrides = listOf(
                GoogleReminderDto(method = "popup", minutes = 30),
                GoogleReminderDto(method = "email", minutes = 10),
                GoogleReminderDto(method = "popup", minutes = 10),
            ),
        ),
        updated = "2026-05-08T21:00:00.000Z",
    )

    @Test
    fun `timed event maps start end reminders and rule`() {
        val mapped = GoogleEventMapper.fromDto(timedDto(), zone, observedAt = 1L)!!

        assertEquals("event-1", mapped.remoteId)
        assertEquals("Созвон", mapped.title)
        assertEquals("Обсудить план", mapped.description)
        val start = Instant.ofEpochMilli(mapped.startTime).atZone(zone).toLocalDateTime()
        val end = Instant.ofEpochMilli(mapped.endTime).atZone(zone).toLocalDateTime()
        assertEquals(LocalDateTime.of(2026, 5, 9, 10, 0), start)
        assertEquals(LocalDateTime.of(2026, 5, 9, 11, 30), end)
        assertEquals(listOf(10, 30), mapped.reminderMinutes)
        assertEquals(RepeatRule.WEEKLY, mapped.repeatRule)
        assertEquals(Instant.parse("2026-05-08T21:00:00Z").toEpochMilli(), mapped.updatedAt)
        assertFalse(mapped.deleted)
    }

    @Test
    fun `all-day event covers the local day`() {
        val dto = GoogleEventDto(
            id = "event-2",
            summary = "Отпуск",
            start = GoogleDateTimeDto(date = "2026-05-09"),
            end = GoogleDateTimeDto(date = "2026-05-10"),
        )

        val mapped = GoogleEventMapper.fromDto(dto, zone, observedAt = 1L)!!
        val start = Instant.ofEpochMilli(mapped.startTime).atZone(zone)
        val end = Instant.ofEpochMilli(mapped.endTime).atZone(zone)

        assertEquals(LocalDate.of(2026, 5, 9), start.toLocalDate())
        assertEquals(0, start.hour)
        assertEquals(LocalDate.of(2026, 5, 9), end.toLocalDate())
        assertEquals(23, end.hour)
        assertEquals(59, end.minute)
        assertEquals(59, end.second)
        assertEquals(999_000_000, end.nano)
    }

    @Test
    fun `cancelled event is a tombstone`() {
        val dto = GoogleEventDto(
            id = "event-3",
            status = "cancelled",
            start = GoogleDateTimeDto(dateTime = "2026-05-09T10:00:00+03:00"),
        )

        assertTrue(GoogleEventMapper.fromDto(dto, zone, observedAt = 1L)!!.deleted)
    }

    @Test
    fun `event without id is skipped`() {
        assertNull(GoogleEventMapper.fromDto(GoogleEventDto(summary = "Без id"), zone, 1L))
    }

    @Test
    fun `write dto carries time, rule and popup reminders`() {
        val event = Event(
            id = "local-1",
            title = "Планёрка",
            description = null,
            startTime = OffsetDateTime.parse("2026-05-09T10:00:00+03:00").toInstant().toEpochMilli(),
            endTime = OffsetDateTime.parse("2026-05-09T11:00:00+03:00").toInstant().toEpochMilli(),
            calendarProvider = CalendarProvider.LOCAL,
            reminderMinutes = listOf(15, 15, 60),
            repeatRule = RepeatRule.WEEKLY,
        )

        val dto = GoogleEventMapper.toWriteDto(event, zone)

        assertEquals("Планёрка", dto.summary)
        assertNull(dto.description)
        assertEquals(
            Instant.ofEpochMilli(event.startTime),
            OffsetDateTime.parse(dto.start.dateTime).toInstant(),
        )
        assertEquals(listOf("RRULE:FREQ=WEEKLY"), dto.recurrence)
        assertEquals(false, dto.reminders.useDefault)
        assertEquals(listOf(15, 60), dto.reminders.overrides!!.map { it.minutes })
        assertTrue(dto.reminders.overrides!!.all { it.method == "popup" })
    }
}
