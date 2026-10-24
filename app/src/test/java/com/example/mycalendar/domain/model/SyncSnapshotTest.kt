package com.example.mycalendar.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Снимок-предок — база для трёхстороннего слияния: он переживает JSON,
 * не видит разницы в порядке напоминаний и умеет налагать выбранные поля.
 */
class SyncSnapshotTest {

    private val base = SyncSnapshot(
        title = "Встреча с мамой",
        description = "Купить молоко и позвонить\nмаме, через 2 дня",
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(15, 5),
        repeatRule = RepeatRule.WEEKLY,
    )

    private val event = Event(
        id = "local-1",
        title = "Встреча с мамой",
        description = base.description,
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(15, 5),
        repeatRule = RepeatRule.WEEKLY,
        textNote = "Локальная заметка",
    )

    @Test
    fun `snapshot survives json round trip`() {
        assertEquals(base, SyncSnapshot.fromJson(base.toJson()))
    }

    @Test
    fun `absent snapshot parses as null`() {
        assertNull(SyncSnapshot.fromJson(null))
    }

    @Test
    fun `garbage snapshot parses as null instead of throwing`() {
        assertNull(SyncSnapshot.fromJson("{не json"))
    }

    @Test
    fun `reminder order is not a difference`() {
        assertEquals(
            emptySet<SyncField>(),
            diffFields(base, base.copy(reminderMinutes = listOf(5, 15))),
        )
    }

    @Test
    fun `missing and blank description are the same`() {
        assertEquals(
            emptySet<SyncField>(),
            diffFields(base.copy(description = null), base.copy(description = "")),
        )
    }

    @Test
    fun `diff lists only changed fields`() {
        assertEquals(
            setOf(SyncField.START_TIME, SyncField.REPEAT_RULE),
            diffFields(base, base.copy(startTime = 3_000L, repeatRule = RepeatRule.DAILY)),
        )
    }

    @Test
    fun `apply replaces only chosen fields`() {
        val cloud = base.copy(title = "Перенос встречи", startTime = 9_000L)

        val merged = cloud.appliedTo(event, setOf(SyncField.TITLE))

        assertEquals("Перенос встречи", merged.title)
        assertEquals(1_000L, merged.startTime)
        assertEquals("Локальная заметка", merged.textNote)
    }

    @Test
    fun `apply changes exactly the chosen field set and leaves startTime`() {
        val cloud = base.copy(
            startTime = 8_000L,
            endTime = 9_000L,
            reminderMinutes = listOf(30),
            repeatRule = RepeatRule.DAILY,
        )

        val merged = cloud.appliedTo(
            event,
            setOf(SyncField.END_TIME, SyncField.REMINDER_MINUTES, SyncField.REPEAT_RULE),
        )

        assertEquals(9_000L, merged.endTime)
        assertEquals(listOf(30), merged.reminderMinutes)
        assertEquals(RepeatRule.DAILY, merged.repeatRule)
        assertEquals(1_000L, merged.startTime)
    }

    @Test
    fun `apply of DESCRIPTION from a null snapshot clears the local description`() {
        val cloud = base.copy(description = null)

        val merged = cloud.appliedTo(event, setOf(SyncField.DESCRIPTION))

        assertNull(merged.description)
    }

    @Test
    fun `apply of no fields keeps event untouched`() {
        assertEquals(event, base.appliedTo(event, emptySet()))
    }

    @Test
    fun `of reads only syncable fields`() {
        assertEquals(base, SyncSnapshot.of(event))
    }
}
