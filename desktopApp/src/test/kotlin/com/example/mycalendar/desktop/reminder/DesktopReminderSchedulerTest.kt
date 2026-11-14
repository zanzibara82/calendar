package com.example.mycalendar.desktop.reminder

import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Исполнитель расписания проверяется на фейковом таймере: тест сам решает,
 * когда «наступает» заказ, поэтому время в тестах не идёт.
 */
class DesktopReminderSchedulerTest {

    private val now = 1_760_000_000_000L
    private val timer = FakeDelayScheduler()
    private val fired = mutableListOf<DueReminder>()
    private val scheduler = DesktopReminderScheduler(
        onReminder = { fired += it },
        clock = { now },
        timer = timer,
    )

    @Test
    fun `schedules reminder before event start`() {
        scheduler.schedule(event(start = now + HOUR, reminders = listOf(10)))

        assertEquals(1, timer.entries.size)
        assertEquals(50 * MINUTE, timer.entries.single().delayMillis)
    }

    @Test
    fun `fires planned reminder with event and minutes`() {
        val event = event(start = now + HOUR, reminders = listOf(10))
        scheduler.schedule(event)

        timer.fire(0)

        assertEquals(listOf(DueReminder(event, 10, now + HOUR)), fired)
    }

    @Test
    fun `reminder in the past for upcoming event fires soon`() {
        scheduler.schedule(event(start = now + 5 * MINUTE, reminders = listOf(10)))

        assertEquals(1_500L, timer.entries.single().delayMillis)
    }

    @Test
    fun `started event schedules nothing`() {
        scheduler.schedule(event(start = now - MINUTE, reminders = listOf(10)))

        assertTrue(timer.entries.isEmpty())
    }

    @Test
    fun `event without reminders schedules nothing`() {
        scheduler.schedule(event(start = now + HOUR))

        assertTrue(timer.entries.isEmpty())
    }

    @Test
    fun `rescheduling cancels the previous plan`() {
        scheduler.schedule(event(start = now + HOUR, reminders = listOf(10)))
        scheduler.schedule(event(start = now + 2 * HOUR, reminders = listOf(10)))

        assertTrue(timer.entries[0].cancelled)
        assertEquals(2 * HOUR - 10 * MINUTE, timer.entries[1].delayMillis)
        assertFalse(timer.entries[1].cancelled)
    }

    @Test
    fun `cancel removes pending reminders`() {
        val event = event(start = now + HOUR, reminders = listOf(10))
        scheduler.schedule(event)

        scheduler.cancel(event)

        assertTrue(timer.entries.single().cancelled)
    }

    @Test
    fun `repeating event plans next occurrence after firing`() {
        val event = event(
            start = now - 3 * DAY,
            reminders = listOf(30),
            rule = RepeatRule.WEEKLY,
        )
        scheduler.schedule(event)
        assertEquals(4 * DAY - 30 * MINUTE, timer.entries.single().delayMillis)

        timer.fire(0)

        assertEquals(2, timer.entries.size)
        assertEquals(11 * DAY - 30 * MINUTE, timer.entries[1].delayMillis)
        assertFalse(timer.entries[1].cancelled)
        assertEquals(1, fired.size)
    }

    @Test
    fun `snooze fires the reminder again after ten minutes`() {
        val event = event(start = now + HOUR, reminders = listOf(10))
        val due = DueReminder(event, 10, now + HOUR)

        scheduler.snooze(due)

        assertEquals(1, timer.entries.size)
        assertEquals(DesktopReminderScheduler.SNOOZE_MINUTES * MINUTE, timer.entries.single().delayMillis)

        timer.fire(0)

        assertEquals(
            listOf(DueReminder(event, DesktopReminderScheduler.SNOOZE_MINUTES_MARKER, now + HOUR)),
            fired,
        )
    }

    @Test
    fun `snoozed repeating reminder does not plan another occurrence`() {
        val event = event(
            start = now - 3 * DAY,
            reminders = listOf(30),
            rule = RepeatRule.WEEKLY,
        )
        scheduler.schedule(event)
        timer.fire(0)
        assertEquals(2, timer.entries.size)

        scheduler.snooze(DueReminder(event, 30, now + 4 * DAY))
        assertEquals(3, timer.entries.size)

        timer.fire(2)

        assertEquals(3, timer.entries.size)
        assertEquals(
            DueReminder(event, DesktopReminderScheduler.SNOOZE_MINUTES_MARKER, now + 4 * DAY),
            fired.last(),
        )
    }

    @Test
    fun `cancel removes the snoozed reminder`() {
        val event = event(start = now + HOUR, reminders = listOf(10))
        scheduler.snooze(DueReminder(event, 10, now + HOUR))

        scheduler.cancel(event)

        assertTrue(timer.entries.single().cancelled)
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

private class FakeDelayScheduler : DelayScheduler {

    class Entry(val delayMillis: Long, val action: () -> Unit) {
        var cancelled = false
    }

    val entries = mutableListOf<Entry>()

    override fun schedule(delayMillis: Long, action: () -> Unit): Cancellable {
        val entry = Entry(delayMillis, action)
        entries += entry
        return Cancellable { entry.cancelled = true }
    }

    fun fire(index: Int) {
        entries[index].action()
    }
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
