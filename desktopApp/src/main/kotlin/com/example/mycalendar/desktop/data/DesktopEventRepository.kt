package com.example.mycalendar.desktop.data

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.SyncSnapshot
import com.example.mycalendar.domain.model.SyncedRecord
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.domain.repository.PendingSyncEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * [EventRepository] поверх файла JSON. Семантика флагов и снимка-предка повторяет
 * Room-реализацию Android, чтобы синхронизация и разбор конфликтов работали одинаково
 * на обоих клиентах.
 */
class DesktopEventRepository(
    private val store: EventFileStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : EventRepository {

    override fun observeEventsBetween(from: Long, to: Long): Flow<List<Event>> =
        observeRecords().map { records ->
            records
                .filter { record ->
                    !record.isDeleted &&
                        record.event.startTime <= to &&
                        (record.event.repeatRule != null || record.event.endTime >= from)
                }
                .map { it.event }
                .sortedBy { it.startTime }
        }

    override fun observeEvent(id: String): Flow<Event?> =
        observeRecords().map { records -> records.firstOrNull { it.event.id == id }?.event }

    override suspend fun getEvent(id: String): Event? =
        store.snapshot().firstOrNull { it.event.id == id }?.event

    override suspend fun getUpcomingEvents(now: Long, limit: Int): List<Event> =
        store.snapshot()
            .asSequence()
            .filter { !it.isDeleted && (it.event.startTime >= now || it.event.repeatRule != null) }
            .map { it.event }
            .sortedBy { it.startTime }
            .take(limit)
            .toList()

    override suspend fun saveFromUser(event: Event) {
        // Правка контента не трогает предка: он сдвигается только за облаком или после push.
        store.update { records ->
            val record = StoredEvent(
                event = event.copy(updatedAt = clock()),
                pendingSync = true,
                syncBase = records.firstOrNull { it.event.id == event.id }?.syncBase,
            )
            records.replacing(record)
        }
    }

    override suspend fun saveFromRemote(event: Event, syncBase: SyncSnapshot?) {
        store.update { records ->
            val record = StoredEvent(
                event = event.copy(updatedAt = event.updatedAt.takeIf { it > 0 } ?: clock()),
                pendingSync = false,
                syncBase = syncBase,
            )
            records.replacing(record)
        }
    }

    override suspend fun markDeleted(id: String) {
        store.update { records ->
            records.map { record ->
                if (record.event.id == id) {
                    record.copy(isDeleted = true, pendingSync = true, event = record.event.copy(updatedAt = clock()))
                } else {
                    record
                }
            }
        }
    }

    override suspend fun hardDelete(id: String) {
        store.update { records -> records.filterNot { it.event.id == id } }
    }

    override suspend fun getPendingSyncEvents(): List<PendingSyncEvent> =
        store.snapshot().filter { it.pendingSync }.map { PendingSyncEvent(it.event, it.isDeleted) }

    override suspend fun markSynced(id: String, syncBase: SyncSnapshot) {
        updateRecord(id) { it.copy(pendingSync = false, syncBase = syncBase) }
    }

    override suspend fun moveSyncBase(id: String, syncBase: SyncSnapshot) {
        updateRecord(id) { it.copy(syncBase = syncBase) }
    }

    override suspend fun attachToProvider(
        id: String,
        remoteId: String,
        provider: CalendarProvider,
        syncBase: SyncSnapshot?,
    ) {
        updateRecord(id) {
            it.copy(
                event = it.event.copy(remoteId = remoteId, calendarProvider = provider),
                pendingSync = false,
                syncBase = syncBase,
            )
        }
    }

    override suspend fun findSyncedRecord(provider: CalendarProvider, remoteId: String): SyncedRecord? =
        store.snapshot()
            .firstOrNull { it.event.calendarProvider == provider && it.event.remoteId == remoteId }
            ?.toSyncedRecord()

    override suspend fun getAttachedRecords(provider: CalendarProvider): List<SyncedRecord> =
        store.snapshot()
            .filter { it.event.calendarProvider == provider && it.event.remoteId != null }
            .map { it.toSyncedRecord() }

    override suspend fun saveResolved(event: Event, syncBase: SyncSnapshot) {
        store.update { records ->
            val record = StoredEvent(
                event = event.copy(updatedAt = clock()),
                pendingSync = true,
                syncBase = syncBase,
            )
            records.replacing(record)
        }
    }

    /**
     * Голосовых заметок в desktop-клиенте пока нет, поэтому удаляется только запись.
     * Когда появится запись голоса, сюда придёт удаление файла — как на Android.
     */
    override suspend fun purge(id: String) {
        hardDelete(id)
    }

    /** Поток строк с гарантированной первоначальной загрузкой файла. */
    private fun observeRecords(): Flow<List<StoredEvent>> = flow {
        store.snapshot()
        emitAll(store.records.filterNotNull())
    }

    private suspend fun updateRecord(id: String, transform: (StoredEvent) -> StoredEvent) {
        store.update { records ->
            records.map { if (it.event.id == id) transform(it) else it }
        }
    }

    private fun StoredEvent.toSyncedRecord() = SyncedRecord(
        event = event,
        isDeleted = isDeleted,
        pendingSync = pendingSync,
        base = syncBase,
    )
}

private fun List<StoredEvent>.replacing(record: StoredEvent): List<StoredEvent> =
    if (any { it.event.id == record.event.id }) {
        map { if (it.event.id == record.event.id) record else it }
    } else {
        this + record
    }
