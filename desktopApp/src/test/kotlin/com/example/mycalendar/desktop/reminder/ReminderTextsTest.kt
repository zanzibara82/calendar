package com.example.mycalendar.desktop.reminder

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** Подпись плашки повторяет строки Android: «Начало в 09:30 (через 5 мин)». */
class ReminderTextsTest {

    private val moscow = ZoneId.of("Europe/Moscow")
    private val start = Instant.parse("2026-11-13T06:30:00Z").toEpochMilli()

    @Test
    fun `minutes label`() {
        assertEquals("Начало в 09:30 (через 5 мин)", reminderLabel(start, 5, moscow))
    }

    @Test
    fun `hours label`() {
        assertEquals("Начало в 09:30 (через 2 ч)", reminderLabel(start, 120, moscow))
    }

    @Test
    fun `days label`() {
        assertEquals("Начало в 09:30 (через 1 д)", reminderLabel(start, 1440, moscow))
    }

    @Test
    fun `partial hour keeps whole hours`() {
        assertEquals("Начало в 09:30 (через 1 ч)", reminderLabel(start, 90, moscow))
    }

    @Test
    fun `non positive minutes means starting now`() {
        assertEquals("Событие начинается", reminderLabel(start, 0, moscow))
    }
}
