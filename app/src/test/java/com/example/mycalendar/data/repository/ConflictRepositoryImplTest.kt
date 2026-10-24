package com.example.mycalendar.data.repository

import com.example.mycalendar.data.local.SyncConflictDao
import com.example.mycalendar.data.local.SyncConflictEntity
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.SyncSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * На событии держится ровно один открытый конфликт: повторное обнаружение
 * перезаписывает существующую строку, не меняя её id (маршрут разбора остаётся валидным).
 */
class ConflictRepositoryImplTest {

    private val dao = FakeSyncConflictDao()
    private val repository = ConflictRepositoryImpl(dao)

    private val remote = SyncSnapshot(
        title = "Название из облака",
        description = "Описание из облака",
        startTime = 5_000L,
        endTime = 6_000L,
        reminderMinutes = listOf(30),
        repeatRule = null,
    )

    private fun conflict(id: String = "c1", eventId: String = "e1") = SyncConflict(
        id = id,
        eventId = eventId,
        provider = CalendarProvider.GOOGLE,
        remoteId = "r1",
        kind = ConflictKind.CONTENT,
        base = remote.copy(title = "Общее название"),
        remote = remote,
        contested = setOf(SyncField.TITLE, SyncField.DESCRIPTION),
        detectedAt = 1_000L,
    )

    @Test
    fun `save keeps contested fields and provider round trip`() = runBlocking {
        repository.save(conflict())

        val stored = repository.get("c1")!!
        assertEquals(CalendarProvider.GOOGLE, stored.provider)
        assertEquals(ConflictKind.CONTENT, stored.kind)
        assertEquals(setOf(SyncField.TITLE, SyncField.DESCRIPTION), stored.contested)
        assertEquals(remote, stored.remote)
    }

    @Test
    fun `second save on same event reuses id and does not add a row`() = runBlocking {
        repository.save(conflict(id = "c1"))

        repository.save(conflict(id = "c-new", eventId = "e1").copy(remote = remote.copy(title = "Ещё правка")))

        assertEquals(1, dao.rows.size)
        assertEquals("c1", repository.get("c1")?.id)
        assertEquals("Ещё правка", repository.get("c1")?.remote?.title)
    }

    /**
     * Повторное обнаружение не передвигает конфликт в конец очереди: время первого
     * обнаружения остаётся, свежие снимки облака при этом записываются.
     */
    @Test
    fun `re-detected conflict keeps the time it was first found`() = runBlocking {
        repository.save(conflict(id = "c1"))

        repository.save(conflict(id = "c-new").copy(detectedAt = 9_000L, remote = remote.copy(title = "Ещё правка")))

        assertEquals(1, dao.rows.size)
        val stored = repository.get("c1")!!
        assertEquals(1_000L, stored.detectedAt)
        assertEquals("Ещё правка", stored.remote.title)
    }

    @Test
    fun `open event ids feed the push gate`() = runBlocking {
        repository.save(conflict(eventId = "e1"))
        repository.save(conflict(id = "c2", eventId = "e2"))

        assertEquals(setOf("e1", "e2"), repository.openEventIds().toSet())
    }

    @Test
    fun `delete removes the conflict from the queue`() = runBlocking {
        repository.save(conflict())

        repository.delete("c1")

        assertTrue(dao.rows.isEmpty())
        assertEquals(0, repository.observeOpenCount().first())
    }

    @Test
    fun `deletion conflict survives empty contested set`() = runBlocking {
        repository.save(conflict().copy(kind = ConflictKind.REMOTE_DELETED, contested = emptySet()))

        val stored = repository.get("c1")!!
        assertEquals(ConflictKind.REMOTE_DELETED, stored.kind)
        assertTrue(stored.isDeletion)
        assertTrue(stored.contested.isEmpty())
    }

    /**
     * Строка, записанная другой версией приложения, не должна ронять разбор:
     * нераспознанный вид становится контентным, провайдер — локальным, битые имена
     * спорных полей отбрасываются, а непарсящееся облачное значение — пустым снимком.
     */
    @Test
    fun `corrupt row falls back to a content conflict and keeps the rest`() = runBlocking {
        dao.rows["c-garbage"] = SyncConflictEntity(
            id = "c-garbage",
            eventId = "e-garbage",
            provider = "no_such_provider",
            remoteId = "r-garbage",
            kind = "NO_SUCH_KIND",
            base = null,
            remote = "не JSON",
            contestedFields = "TITLE,BOGUS",
            detectedAt = 2_000L,
        )

        val stored = repository.get("c-garbage")!!
        assertEquals(ConflictKind.CONTENT, stored.kind)
        assertEquals(CalendarProvider.LOCAL, stored.provider)
        assertEquals(setOf(SyncField.TITLE), stored.contested)
        assertEquals("e-garbage", stored.eventId)
        assertEquals("r-garbage", stored.remoteId)
        assertEquals(2_000L, stored.detectedAt)
    }
}

/** Таблица конфликтов в памяти — проверка маппинга и «один конфликт на событие» без Android. */
private class FakeSyncConflictDao : SyncConflictDao {
    val rows = mutableMapOf<String, SyncConflictEntity>()

    override fun observeAll(): Flow<List<SyncConflictEntity>> = flowOf(rows.values.sortedBy { it.detectedAt })
    override fun observeCount(): Flow<Int> = flowOf(rows.size)
    override suspend fun openEventIds(): List<String> = rows.values.map { it.eventId }
    override suspend fun getById(id: String): SyncConflictEntity? = rows[id]
    override suspend fun getByEvent(eventId: String): SyncConflictEntity? =
        rows.values.firstOrNull { it.eventId == eventId }
    override suspend fun upsert(conflict: SyncConflictEntity) { rows[conflict.id] = conflict }
    override suspend fun deleteById(id: String) { rows.remove(id) }
}
