package com.example.mycalendar.data.repository

import com.example.mycalendar.data.local.EventDao
import com.example.mycalendar.data.local.EventEntity
import com.example.mycalendar.data.local.EventSyncState
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import com.example.mycalendar.domain.model.SyncSnapshot
import com.example.mycalendar.domain.repository.VoiceNoteCleanup
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val VOICE_PATH = "/data/user/0/com.example.mycalendar/files/voice_notes/e1.m4a"

/**
 * Служебные колонки строки (предок, pendingSync) переживают правки контента:
 * saveFromUser не должен стирать syncBase, а запись из облака — обязан его проставлять.
 */
class EventRepositorySyncStateTest {

    private val dao = FakeEventDao()
    private val voiceNotes = FakeVoiceNoteStorage()
    private val repository = EventRepositoryImpl(dao, voiceNotes)

    private val base = SyncSnapshot(
        title = "Встреча",
        description = null,
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(5),
        repeatRule = RepeatRule.WEEKLY,
    )

    private val event = Event(
        id = "e1",
        title = "Встреча",
        description = null,
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(5),
        repeatRule = RepeatRule.WEEKLY,
        calendarProvider = CalendarProvider.GOOGLE,
        remoteId = "r1",
    )

    @Test
    fun `saveFromUser keeps predecessor`() = kotlinx.coroutines.runBlocking {
        repository.saveFromRemote(event, base)

        repository.saveFromUser(event.copy(title = "Встреча перенесена"))

        assertEquals(base, SyncSnapshot.fromJson(dao.rows.getValue("e1").syncBase))
        assertTrue(dao.rows.getValue("e1").pendingSync)
    }

    @Test
    fun `saveFromRemote writes predecessor and clears pending flag`() = kotlinx.coroutines.runBlocking {
        repository.saveFromRemote(event, base)

        val stored = dao.rows.getValue("e1")
        assertEquals(base.toJson(), stored.syncBase)
        assertTrue(!stored.pendingSync)
    }

    @Test
    fun `findSyncedRecord exposes sync state of the row`() = kotlinx.coroutines.runBlocking {
        repository.saveFromRemote(event, base)
        dao.rows.getValue("e1").let { dao.rows["e1"] = it.copy(isDeleted = true) }

        val record = repository.findSyncedRecord(CalendarProvider.GOOGLE, "r1")

        assertEquals("e1", record?.event?.id)
        assertTrue(record?.isDeleted == true)
        assertEquals(false, record?.pendingSync)
        assertEquals(base, record?.base)
    }

    @Test
    fun `findSyncedRecord returns null for unknown remote id`() = kotlinx.coroutines.runBlocking {
        assertNull(repository.findSyncedRecord(CalendarProvider.YANDEX, "нет-такого"))
    }

    @Test
    fun `markSynced moves predecessor and clears pending flag`() = kotlinx.coroutines.runBlocking {
        repository.saveFromRemote(event, base)
        val pushed = base.copy(title = "Встреча перенесена")

        repository.markSynced("e1", pushed)

        val stored = dao.rows.getValue("e1")
        assertTrue(!stored.pendingSync)
        assertEquals(pushed, SyncSnapshot.fromJson(stored.syncBase))
    }

    @Test
    fun `saveResolved stores the user choice as the new predecessor`() = kotlinx.coroutines.runBlocking {
        repository.saveFromRemote(event, base)
        val resolved = event.copy(title = "Встреча — выбор пользователя")
        val newBase = SyncSnapshot.of(resolved)

        repository.saveResolved(resolved, newBase)

        val stored = dao.rows.getValue("e1")
        assertEquals("Встреча — выбор пользователя", stored.title)
        assertTrue(stored.pendingSync)
        assertEquals(newBase, SyncSnapshot.fromJson(stored.syncBase))
    }

    @Test
    fun `purge drops the row`() = kotlinx.coroutines.runBlocking {
        repository.saveFromRemote(event, base)

        repository.purge("e1")

        assertTrue(dao.rows.isEmpty())
        assertTrue(voiceNotes.deletedPaths.isEmpty())
    }

    @Test
    fun `purge removes the voice file together with the row`() = kotlinx.coroutines.runBlocking {
        repository.saveFromRemote(event.copy(voiceNotePath = VOICE_PATH), base)

        repository.purge("e1")

        assertTrue(dao.rows.isEmpty())
        assertEquals(listOf(VOICE_PATH), voiceNotes.deletedPaths)
    }

    @Test
    fun `purge drops the row even when the file system refuses to delete`() = kotlinx.coroutines.runBlocking {
        repository.saveFromRemote(event.copy(voiceNotePath = VOICE_PATH), base)
        // Файл «занят»: подмена сообщает об отказе, но путь всё равно запрошен на удаление.
        voiceNotes.deleteResult = false

        repository.purge("e1")

        assertTrue(dao.rows.isEmpty())
        assertEquals(listOf(VOICE_PATH), voiceNotes.deletedPaths)
    }
}

/** Room-DAO в памяти: достаточно для проверки служебных колонок, без Android. */
private class FakeEventDao : EventDao {
    val rows = mutableMapOf<String, EventEntity>()

    override fun observeEventsBetween(from: Long, to: Long): Flow<List<EventEntity>> = kotlinx.coroutines.flow.flowOf(rows.values.toList())
    override fun observeEvent(id: String): Flow<EventEntity?> = kotlinx.coroutines.flow.flowOf(rows[id])
    override suspend fun getEvent(id: String): EventEntity? = rows[id]
    override suspend fun getUpcoming(now: Long, limit: Int): List<EventEntity> = rows.values.toList()
    override suspend fun upsert(event: EventEntity) { rows[event.id] = event }
    override suspend fun softDelete(id: String, updatedAt: Long) {
        rows[id] = rows.getValue(id).copy(isDeleted = true, pendingSync = true, updatedAt = updatedAt)
    }
    override suspend fun hardDelete(id: String) { rows.remove(id) }
    override suspend fun getPendingSync(): List<EventEntity> = rows.values.filter { it.pendingSync }
    override suspend fun markSynced(id: String, syncBase: String?) {
        rows[id] = rows.getValue(id).copy(pendingSync = false, syncBase = syncBase)
    }
    override suspend fun attachToProvider(id: String, remoteId: String, provider: String, syncBase: String?) {
        rows[id] = rows.getValue(id).copy(remoteId = remoteId, calendarProvider = provider, pendingSync = false, syncBase = syncBase)
    }
    override suspend fun findByRemoteId(provider: String, remoteId: String): EventEntity? =
        rows.values.firstOrNull { it.calendarProvider == provider && it.remoteId == remoteId }
    override suspend fun getSyncState(id: String): EventSyncState? =
        rows[id]?.let { EventSyncState(it.syncBase, it.updatedAt) }
}

/** Фикс чистки заметок: записывает запрошенные на удаление пути и отвечает за результат. */
private class FakeVoiceNoteStorage : VoiceNoteCleanup {
    val deletedPaths = mutableListOf<String>()
    var deleteResult = true

    override fun deleteFile(absolutePath: String): Boolean {
        deletedPaths += absolutePath
        return deleteResult
    }
}
