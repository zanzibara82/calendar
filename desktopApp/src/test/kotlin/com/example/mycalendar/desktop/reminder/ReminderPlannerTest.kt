package com.example.mycalendar.desktop.reminder

import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Расписание напоминаний повторяет правила Android: ближайшее вхождение, прошедшее
 * напоминание при ещё не начавшемся событии срабатывает сразу, начавшееся — пропускается.
 */
class ReminderPlannerTest {

    private val now = 1_760_000_000_000L

    @Test
    fun `plans reminder before event start`() {
        val event = event(start = now + HOUR, reminders = listOf(10))

        val planned = ReminderPlanner.plan(event, now)

        assertEquals(1, planned.size)
        assertEquals(now + HOUR - 10 * MINUTE, planned[0].triggerAt)
        assertEquals(now + HOUR, planned[0].occurrenceStart)
        assertEquals(10, planned[0].minutes)
    }

    @Test
    fun `reminder time passed but event not started fires soon`() {
        val event = event(start = now + 5 * MINUTE, reminders = listOf(10))

        val planned = ReminderPlanner.plan(event, now)

        assertEquals(1, planned.size)
        assertEquals(now + 1_500L, planned[0].triggerAt)
        assertEquals(now + 5 * MINUTE, planned[0].occurrenceStart)
    }

    @Test
    fun `started event plans nothing`() {
        val event = event(start = now - 5 * MINUTE, reminders = listOf(10))

        assertTrue(ReminderPlanner.plan(event, now).isEmpty())
    }

    @Test
    fun `past event without repeat plans nothing`() {
        val event = event(start = now - DAY, reminders = listOf(10))

        assertTrue(ReminderPlanner.plan(event, now).isEmpty())
    }

    @Test
    fun `event without reminders plans nothing`() {
        val event = event(start = now + HOUR)

        assertTrue(ReminderPlanner.plan(event, now).isEmpty())
    }

    @Test
    fun `duplicate minutes planned once`() {
        val event = event(start = now + HOUR, reminders = listOf(10, 10))

        assertEquals(1, ReminderPlanner.plan(event, now).size)
    }

    @Test
    fun `repeating event plans next occurrence`() {
        val event = event(
            start = now - 3 * DAY,
            reminders = listOf(30),
            rule = RepeatRule.WEEKLY,
        )

        val planned = ReminderPlanner.plan(event, now)

        assertEquals(1, planned.size)
        assertEquals(now + 4 * DAY, planned[0].occurrenceStart)
        assertEquals(now + 4 * DAY - 30 * MINUTE, planned[0].triggerAt)
    }

    @Test
    fun `repeating event with future start plans its first occurrence`() {
        val event = event(
            start = now + 2 * DAY,
            reminders = listOf(30),
            rule = RepeatRule.WEEKLY,
        )

        val planned = ReminderPlanner.plan(event, now)

        assertEquals(1, planned.size)
        assertEquals(now + 2 * DAY, planned[0].occurrenceStart)
        assertEquals(now + 2 * DAY - 30 * MINUTE, planned[0].triggerAt)
    }

    @Test
    fun `next occurrence continues the chain`() {
        val event = event(
            start = now - 3 * DAY,
            reminders = listOf(30),
            rule = RepeatRule.WEEKLY,
        )
        val previousStart = now + 4 * DAY

        val planned = ReminderPlanner.planNext(event, previousStart, now)

        assertEquals(1, planned.size)
        assertEquals(now + 11 * DAY, planned[0].occurrenceStart)
        assertEquals(now + 11 * DAY - 30 * MINUTE, planned[0].triggerAt)
    }

    private fun event(
        start: Long,
        reminders: List<Int> = emptyList(),
        rule: RepeatRule? = null,
    ) = Event(
        id = "evt",
        title = "Событие",
        startTime = start,
        endTime = start + 30 * MINUTE,
        reminderMinutes = reminders,
        repeatRule = rule,
    )
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
