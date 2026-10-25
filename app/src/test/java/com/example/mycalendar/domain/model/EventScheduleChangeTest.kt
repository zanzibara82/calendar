package com.example.mycalendar.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Заказ будильников зависит только от времени, минут и правила повторения: название, описание,
 * метки и заметки попадают в событие из базы при срабатывании и перепланирования не требуют.
 */
class EventScheduleChangeTest {

    private val event = Event(
        id = "e1",
        title = "Встреча",
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(15, 5),
        repeatRule = RepeatRule.WEEKLY,
    )

    @Test
    fun `title and description change keeps the alarm order`() {
        val edited = event.copy(title = "Встреча перенесена", description = "кабинет 2")

        assertFalse(edited.scheduleChangedFrom(event))
    }

    @Test
    fun `start or end shift asks for reschedule`() {
        assertTrue(event.copy(startTime = 1_500L).scheduleChangedFrom(event))
        assertTrue(event.copy(endTime = 2_500L).scheduleChangedFrom(event))
    }

    @Test
    fun `reminder order does not count but composition does`() {
        assertFalse(event.copy(reminderMinutes = listOf(5, 15)).scheduleChangedFrom(event))
        assertTrue(event.copy(reminderMinutes = listOf(15, 5, 60)).scheduleChangedFrom(event))
        assertTrue(event.copy(reminderMinutes = emptyList()).scheduleChangedFrom(event))
    }

    @Test
    fun `repeat rule change asks for reschedule`() {
        assertTrue(event.copy(repeatRule = null).scheduleChangedFrom(event))
        assertTrue(event.copy(repeatRule = RepeatRule.DAILY).scheduleChangedFrom(event))
    }

    @Test
    fun `local notes do not ask for reschedule`() {
        val edited = event.copy(tags = listOf(EventTag.CINEMA), textNote = "взять паспорт")

        assertFalse(edited.scheduleChangedFrom(event))
    }
}
