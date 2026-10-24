package com.example.mycalendar

import com.example.mycalendar.data.remote.RemoteEvent
import com.example.mycalendar.data.remote.toEvent
import com.example.mycalendar.data.repository.PullDecision
import com.example.mycalendar.data.repository.SyncConflictResolver
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.SyncSnapshot
import com.example.mycalendar.domain.model.SyncedRecord
import com.example.mycalendar.domain.model.diffFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Матрица решений pull (раздел 2 дизайна): кто изменился относительно предка —
 * облако, локально, оба или никто. Время изменения в расчёте не участвует.
 */
class SyncConflictResolverTest {

    private val resolver = SyncConflictResolver()

    private val base = SyncSnapshot(
        title = "Общее название",
        description = "Общее описание",
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(5, 15),
        repeatRule = RepeatRule.WEEKLY,
    )

    private fun localEvent(fields: SyncSnapshot.() -> SyncSnapshot = { this }) =
        base.fields().let {
            Event(
                id = "e1",
                title = it.title,
                description = it.description,
                startTime = it.startTime,
                endTime = it.endTime,
                reminderMinutes = it.reminderMinutes,
                repeatRule = it.repeatRule,
                textNote = "Локальная заметка",
                calendarProvider = CalendarProvider.GOOGLE,
                remoteId = "r1",
                updatedAt = 100L,
            )
        }

    private fun record(
        event: Event = localEvent(),
        isDeleted: Boolean = false,
        pendingSync: Boolean = false,
        base: SyncSnapshot? = this.base,
    ) = SyncedRecord(event = event, isDeleted = isDeleted, pendingSync = pendingSync, base = base)

    private fun remoteEvent(fields: SyncSnapshot.() -> SyncSnapshot = { this }): RemoteEvent =
        base.fields().let {
            RemoteEvent(
                remoteId = "r1",
                title = it.title,
                description = it.description,
                startTime = it.startTime,
                endTime = it.endTime,
                reminderMinutes = it.reminderMinutes,
                repeatRule = it.repeatRule,
                updatedAt = 900L,
            )
        }

    private fun deletedRemote() = remoteEvent().copy(deleted = true)

    private fun decide(record: SyncedRecord?, remote: RemoteEvent) =
        resolver.decide(record, remote, CalendarProvider.GOOGLE)

    @Test
    fun `no local pair inserts cloud version as its own predecessor`() {
        val changed = remoteEvent { copy(title = "Название из облака") }

        val decision = decide(record = null, remote = changed)

        assertTrue(decision is PullDecision.Insert)
        decision as PullDecision.Insert
        assertEquals("Название из облака", decision.event.title)
        assertEquals(SyncSnapshot.of(changed.toEvent(CalendarProvider.GOOGLE)), decision.base)
    }

    @Test
    fun `cloud unchanged relative to predecessor skips the row`() {
        val decision = decide(record(), remoteEvent())

        assertEquals(PullDecision.Skip, decision)
    }

    @Test
    fun `only cloud changed applies silently and moves predecessor`() {
        val decision = decide(record(), remoteEvent { copy(title = "Название из облака") })

        decision as PullDecision.ApplyRemote
        assertEquals("Название из облака", decision.event.title)
        assertEquals("Локальная заметка", decision.event.textNote)
        assertEquals(setOf(SyncField.TITLE), diffFields(base, decision.base))
    }

    @Test
    fun `only local changed keeps local and queues nothing`() {
        val decision = decide(
            record(event = localEvent { copy(title = "Моя правка") }, pendingSync = true),
            remoteEvent(),
        )

        assertEquals(PullDecision.Skip, decision)
    }

    @Test
    fun `disjoint changes merge automatically without moving predecessor`() {
        val decision = decide(
            record(event = localEvent { copy(title = "Моя правка") }, pendingSync = true),
            remoteEvent { copy(startTime = 7_000L, endTime = 8_000L) },
        )

        decision as PullDecision.AutoMerge
        assertEquals("Моя правка", decision.event.title)
        assertEquals(7_000L, decision.event.startTime)
        assertEquals(8_000L, decision.event.endTime)
        assertEquals("Локальная заметка", decision.event.textNote)
    }

    @Test
    fun `unpushed state equal to predecessor is not overwritten`() {
        val decision = decide(
            record(event = localEvent(), pendingSync = true),
            remoteEvent { copy(title = "Название из облака") },
        )

        assertEquals(PullDecision.Skip, decision)
    }

    @Test
    fun `both sides reaching the same value contest nothing`() {
        val decision = decide(
            record(
                event = localEvent { copy(title = "Финальное название", description = "Локальное описание") },
                pendingSync = true,
            ),
            remoteEvent { copy(title = "Финальное название") },
        )

        assertEquals(PullDecision.Skip, decision)
    }

    @Test
    fun `repeated pull of an already merged row skips`() {
        val decision = decide(
            record(
                event = localEvent { copy(title = "Моя правка", startTime = 7_000L, endTime = 8_000L) },
                pendingSync = true,
            ),
            remoteEvent { copy(startTime = 7_000L, endTime = 8_000L) },
        )

        assertEquals(PullDecision.Skip, decision)
    }

    @Test
    fun `overlapping field goes to the queue and local value survives`() {
        val decision = decide(
            record(event = localEvent { copy(title = "Моя правка") }, pendingSync = true),
            remoteEvent { copy(title = "Правка из облака", startTime = 7_000L) },
        )

        decision as PullDecision.Conflict
        assertEquals(ConflictKind.CONTENT, decision.kind)
        assertEquals(setOf(SyncField.TITLE), decision.contested)
        assertEquals("Моя правка", decision.mergedLocal.title)
        assertEquals(7_000L, decision.mergedLocal.startTime)
        assertEquals("Правка из облака", decision.remote.title)
    }

    @Test
    fun `reminder order difference is not a conflict`() {
        val decision = decide(
            record(event = localEvent { copy(reminderMinutes = listOf(15, 5)) }, pendingSync = true),
            remoteEvent { copy(reminderMinutes = listOf(5, 15)) },
        )

        assertEquals(PullDecision.Skip, decision)
    }

    @Test
    fun `legacy row without predecessor and without local edits adopts cloud`() {
        val decision = decide(
            record(event = localEvent(), base = null, pendingSync = false),
            remoteEvent { copy(title = "Название из облака") },
        )

        assertTrue(decision is PullDecision.ApplyRemote)
    }

    @Test
    fun `legacy row without predecessor and with local edits contests every difference`() {
        val decision = decide(
            record(event = localEvent { copy(title = "Моя правка", startTime = 3_000L) }, base = null, pendingSync = true),
            remoteEvent { copy(title = "Правка из облака") },
        )

        decision as PullDecision.Conflict
        // Предка нет: спорно каждое поле, где локальная строка и облако разошлись, —
        // здесь это и TITLE, и START_TIME (обе правки локальны, base == null).
        assertEquals(setOf(SyncField.TITLE, SyncField.START_TIME), decision.contested)
        assertEquals("Моя правка", decision.mergedLocal.title)
        assertEquals(3_000L, decision.mergedLocal.startTime)
    }

    @Test
    fun `legacy row identical to cloud does nothing`() {
        val decision = decide(
            record(event = localEvent(), base = null, pendingSync = true),
            remoteEvent(),
        )

        assertEquals(PullDecision.Skip, decision)
    }

    @Test
    fun `cloud deletion of untouched row purges local`() {
        assertEquals(PullDecision.Purge, decide(record(), deletedRemote()))
    }

    @Test
    fun `cloud deletion of locally edited row queues conflict`() {
        val decision = decide(
            record(event = localEvent { copy(title = "Моя правка") }, pendingSync = true),
            deletedRemote(),
        )

        decision as PullDecision.Conflict
        assertEquals(ConflictKind.REMOTE_DELETED, decision.kind)
        assertEquals("e1", decision.eventId)
        assertTrue(decision.contested.isEmpty())
        assertEquals("Моя правка", decision.mergedLocal.title)
    }

    @Test
    fun `cloud deletion of local tombstone purges`() {
        val decision = decide(record(isDeleted = true, pendingSync = true), deletedRemote())

        assertEquals(PullDecision.Purge, decision)
    }

    @Test
    fun `local tombstone with unchanged cloud is pushed as delete`() {
        assertEquals(PullDecision.PushDelete, decide(record(isDeleted = true, pendingSync = true), remoteEvent()))
    }

    @Test
    fun `local tombstone with edited cloud queues conflict`() {
        val decision = decide(
            record(isDeleted = true, pendingSync = true),
            remoteEvent { copy(title = "Правка из облака") },
        )

        decision as PullDecision.Conflict
        assertEquals(ConflictKind.LOCAL_DELETED, decision.kind)
        assertEquals(setOf(SyncField.TITLE), decision.contested)
    }

    @Test
    fun `tombstone without predecessor deletes silently`() {
        val decision = decide(
            record(isDeleted = true, pendingSync = true, base = null),
            remoteEvent { copy(title = "Правка из облака") },
        )

        assertEquals(PullDecision.PushDelete, decision)
    }
}

/**
 * Перенесённые прежние случаи last-write-wins. Класс временный: он держит покрытие
 * над методом resolve(...), который до задачи 6 ещё вызывает продакшн-код
 * SyncRepositoryImpl, и исчезает вместе с этим методом в задаче 6.
 */
class LwwTransitionTest {

    private val resolver = SyncConflictResolver()

    private fun local(updatedAt: Long) = Event(
        id = "local-1",
        title = "Локальное событие",
        startTime = 1_000L,
        endTime = 2_000L,
        updatedAt = updatedAt,
    )

    private fun remote(updatedAt: Long) = RemoteEvent(
        remoteId = "remote-1",
        title = "Удалённое событие",
        description = null,
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = emptyList(),
        repeatRule = RepeatRule.WEEKLY,
        updatedAt = updatedAt,
    )

    @Test
    fun `remote newer wins`() {
        val resolution = resolver.resolve(local(updatedAt = 100), remote(updatedAt = 200))
        assertEquals(SyncConflictResolver.Resolution.TAKE_REMOTE, resolution)
    }

    @Test
    fun `local newer wins`() {
        val resolution = resolver.resolve(local(updatedAt = 300), remote(updatedAt = 200))
        assertEquals(SyncConflictResolver.Resolution.KEEP_LOCAL, resolution)
    }

    @Test
    fun `equal timestamps prefer local`() {
        val resolution = resolver.resolve(local(updatedAt = 200), remote(updatedAt = 200))
        assertEquals(SyncConflictResolver.Resolution.KEEP_LOCAL, resolution)
    }
}
