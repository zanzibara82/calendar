package com.example.mycalendar

import com.example.mycalendar.data.remote.RemoteEvent
import com.example.mycalendar.data.remote.toEvent
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.EventTag
import com.example.mycalendar.domain.model.RepeatRule
import com.example.mycalendar.domain.model.withLocalFieldsFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pull не должен стирать локальные поля: метки, текстовую и голосовую заметки
 * провайдер не хранит и не возвращает, поэтому они переносятся из локальной записи.
 */
class SyncPullLocalFieldsTest {

    private val remote = RemoteEvent(
        remoteId = "remote-1",
        title = "Название из облака",
        description = "Описание из облака",
        startTime = 5_000L,
        endTime = 6_000L,
        reminderMinutes = listOf(30),
        repeatRule = RepeatRule.DAILY,
        updatedAt = 200L,
    )

    private val local = Event(
        id = "local-1",
        title = "Локальное название",
        description = "Локальное описание",
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(5, 15),
        repeatRule = RepeatRule.WEEKLY,
        textNote = "Купить молоко и позвонить маме",
        tags = listOf(EventTag.BIRTHDAY, EventTag.CINEMA),
        voiceNotePath = "/data/user/0/com.example.mycalendar/files/voice_notes/1.m4a",
        calendarProvider = CalendarProvider.GOOGLE,
        remoteId = "remote-1",
        updatedAt = 100L,
    )

    private fun incoming() = remote.toEvent(CalendarProvider.GOOGLE).withLocalFieldsFrom(local)

    @Test
    fun `remote overwrite keeps local tags notes and id`() {
        val merged = incoming()

        assertEquals(local.id, merged.id)
        assertEquals(local.tags, merged.tags)
        assertEquals(local.textNote, merged.textNote)
        assertEquals(local.voiceNotePath, merged.voiceNotePath)
    }

    @Test
    fun `remote overwrite takes synced fields from cloud`() {
        val merged = incoming()

        assertEquals(remote.title, merged.title)
        assertEquals(remote.description, merged.description)
        assertEquals(remote.startTime, merged.startTime)
        assertEquals(remote.endTime, merged.endTime)
        assertEquals(remote.reminderMinutes, merged.reminderMinutes)
        assertEquals(remote.repeatRule, merged.repeatRule)
        assertEquals(remote.updatedAt, merged.updatedAt)
        assertEquals(remote.remoteId, merged.remoteId)
    }

    @Test
    fun `cleared local note is not resurrected by pull`() {
        val localWithoutNotes = local.copy(
            textNote = null,
            tags = emptyList(),
            voiceNotePath = null,
        )

        val merged = remote.toEvent(CalendarProvider.GOOGLE).withLocalFieldsFrom(localWithoutNotes)

        assertNull(merged.textNote)
        assertNull(merged.voiceNotePath)
        assertTrue(merged.tags.isEmpty())
    }

    @Test
    fun `event without local pair is stored without local fields`() {
        val incoming = remote.toEvent(CalendarProvider.GOOGLE)

        assertNull(incoming.textNote)
        assertNull(incoming.voiceNotePath)
        assertTrue(incoming.tags.isEmpty())
        assertEquals(CalendarProvider.GOOGLE, incoming.calendarProvider)
        assertEquals(remote.remoteId, incoming.remoteId)
    }
}
