package com.example.mycalendar.domain.reminder

import com.example.mycalendar.domain.model.RepeatRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Выбор вхождения, к которому привязывается напоминание.
 */
class ReminderOccurrenceHelperTest {

    private val now = 1_760_000_000_000L

    @Test
    fun `future event is its own occurrence`() {
        val start = now + HOUR

        assertEquals(start, ReminderOccurrenceHelper.closestOccurrenceStart(start, null, now))
    }

    @Test
    fun `future repeating event keeps its first occurrence`() {
        val start = now + 2 * DAY

        assertEquals(
            start,
            ReminderOccurrenceHelper.closestOccurrenceStart(start, RepeatRule.WEEKLY, now),
        )
    }

    @Test
    fun `started repeating event moves to next occurrence`() {
        val start = now - 3 * DAY

        assertEquals(
            now + 4 * DAY,
            ReminderOccurrenceHelper.closestOccurrenceStart(start, RepeatRule.WEEKLY, now),
        )
    }

    @Test
    fun `started event without repeat has no occurrence`() {
        assertNull(ReminderOccurrenceHelper.closestOccurrenceStart(now - HOUR, null, now))
    }

    @Test
    fun `event starting exactly now is not upcoming`() {
        assertNull(ReminderOccurrenceHelper.closestOccurrenceStart(now, null, now))
    }
}

private const val HOUR = 60 * 60_000L
private const val DAY = 24 * HOUR
