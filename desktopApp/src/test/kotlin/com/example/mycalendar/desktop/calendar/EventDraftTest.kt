package com.example.mycalendar.desktop.calendar

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.EventTag
import com.example.mycalendar.domain.model.RepeatRule
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDraftTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val day = LocalDate.of(2026, 11, 12)

    private fun millis(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `new draft points at the chosen day with a one hour slot`() {
        val draft = draftForNew(day)

        assertEquals("", draft.title)
        assertEquals("12.11.2026", draft.date)
        assertEquals("09:00", draft.start)
        assertEquals("10:00", draft.end)
        assertNull(draft.repeatRule)
    }

    @Test
    fun `valid draft builds a local event`() {
        val result = buildEvent(
            draft = EventDraft("Планёрка", "12.11.2026", "9:30", "11:00", RepeatRule.WEEKLY),
            id = "new",
            zone = zone,
        )

        val event = result.event!!
        assertEquals("new", event.id)
        assertEquals("Планёрка", event.title)
        assertEquals(millis(day, 9, 30), event.startTime)
        assertEquals(millis(day, 11), event.endTime)
        assertEquals(RepeatRule.WEEKLY, event.repeatRule)
        assertEquals(CalendarProvider.LOCAL, event.calendarProvider)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `blank title is rejected`() {
        val result = buildEvent(EventDraft("   ", "12.11.2026", "09:00", "10:00"), "new", zone = zone)

        assertNull(result.event)
        assertEquals("Введите название", result.errors[DraftField.TITLE])
    }

    @Test
    fun `date in another format is rejected`() {
        val result = buildEvent(EventDraft("Событие", "2026-11-12", "09:00", "10:00"), "new", zone = zone)

        assertNull(result.event)
        assertEquals("Дата в формате дд.мм.гггг", result.errors[DraftField.DATE])
    }

    @Test
    fun `impossible date is rejected`() {
        val result = buildEvent(EventDraft("Событие", "31.02.2026", "09:00", "10:00"), "new", zone = zone)

        assertNull(result.event)
        assertEquals("Дата в формате дд.мм.гггг", result.errors[DraftField.DATE])
    }

    @Test
    fun `bad start time is rejected`() {
        val result = buildEvent(EventDraft("Событие", "12.11.2026", "25:00", "10:00"), "new", zone = zone)

        assertNull(result.event)
        assertEquals("Время в формате ЧЧ:ММ", result.errors[DraftField.START])
    }

    @Test
    fun `end not after start is rejected`() {
        val result = buildEvent(EventDraft("Событие", "12.11.2026", "10:00", "10:00"), "new", zone = zone)

        assertNull(result.event)
        assertEquals("Конец должен быть позже начала", result.errors[DraftField.END])
    }

    @Test
    fun `edit draft keeps id and local fields of the base event`() {
        val base = Event(
            id = "e1",
            title = "Старое",
            description = "описание",
            startTime = millis(day, 9),
            endTime = millis(day, 10),
            reminderMinutes = listOf(15),
            repeatRule = RepeatRule.MONTHLY,
            textNote = "заметка",
            tags = listOf(EventTag.BIRTHDAY),
            calendarProvider = CalendarProvider.YANDEX,
            remoteId = "remote-1",
        )

        val result = buildEvent(
            draft = draftFromEvent(base, zone).copy(title = "Новое", start = "18:00", end = "19:00"),
            id = "ignored",
            base = base,
            zone = zone,
        )

        val event = result.event!!
        assertEquals("e1", event.id)
        assertEquals("Новое", event.title)
        assertEquals("описание", event.description)
        assertEquals(listOf(15), event.reminderMinutes)
        assertEquals(RepeatRule.MONTHLY, event.repeatRule)
        assertEquals("заметка", event.textNote)
        assertEquals(listOf(EventTag.BIRTHDAY), event.tags)
        assertEquals(CalendarProvider.YANDEX, event.calendarProvider)
        assertEquals("remote-1", event.remoteId)
        assertEquals(millis(day, 18), event.startTime)
        assertEquals(millis(day, 19), event.endTime)
    }

    @Test
    fun `draft from event shows date and times in the given zone`() {
        val event = Event(
            id = "e1",
            title = "Событие",
            startTime = LocalDateTime.of(2026, 11, 12, 7, 5).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(),
            endTime = LocalDateTime.of(2026, 11, 12, 8, 35).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(),
            repeatRule = RepeatRule.DAILY,
        )

        val draft = draftFromEvent(event, zone)

        assertEquals("Событие", draft.title)
        assertEquals("12.11.2026", draft.date)
        assertEquals("10:05", draft.start)
        assertEquals("11:35", draft.end)
        assertEquals(RepeatRule.DAILY, draft.repeatRule)
    }
}
