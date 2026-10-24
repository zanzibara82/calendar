package com.example.mycalendar.data.repository

import com.example.mycalendar.data.local.EventDao
import com.example.mycalendar.data.local.toDomain
import com.example.mycalendar.data.local.toEntity
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.SyncSnapshot
import com.example.mycalendar.domain.model.SyncedRecord
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.domain.repository.PendingSyncEvent
import com.example.mycalendar.domain.repository.VoiceNoteCleanup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Реализация [EventRepository] поверх Room.
 *
 * Ключевая логика синхронизации:
 *  - saveFromUser — любое изменение пользователя помечает запись pendingSync и
 *    обновляет updatedAt (для сравнения версий при last-write-wins);
 *  - saveFromRemote — запись из облака НЕ помечается pendingSync (иначе цикл синхронизации).
 */
@Singleton
class EventRepositoryImpl @Inject constructor(
    private val eventDao: EventDao,
    private val voiceNotes: VoiceNoteCleanup,
) : EventRepository {

    override fun observeEventsBetween(from: Long, to: Long): Flow<List<Event>> =
        eventDao.observeEventsBetween(from, to).map { list -> list.map { it.toDomain() } }

    override fun observeEvent(id: String): Flow<Event?> =
        eventDao.observeEvent(id).map { it?.toDomain() }

    override suspend fun getEvent(id: String): Event? =
        eventDao.getEvent(id)?.toDomain()

    override suspend fun getUpcomingEvents(now: Long, limit: Int): List<Event> =
        eventDao.getUpcoming(now, limit).map { it.toDomain() }

    override suspend fun saveFromUser(event: Event) {
        // Правка контента не трогает предка: он сдвигается только за облаком или после push.
        eventDao.upsert(
            event.toEntity(
                updatedAt = System.currentTimeMillis(),
                pendingSync = true,
                syncBase = eventDao.getSyncState(event.id)?.syncBase,
            )
        )
    }

    override suspend fun saveFromRemote(event: Event, syncBase: SyncSnapshot?) {
        eventDao.upsert(
            event.toEntity(
                updatedAt = event.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                pendingSync = false,
                syncBase = syncBase?.toJson(),
            )
        )
    }

    override suspend fun markDeleted(id: String) {
        eventDao.softDelete(id, System.currentTimeMillis())
    }

    override suspend fun hardDelete(id: String) = eventDao.hardDelete(id)

    override suspend fun getPendingSyncEvents(): List<PendingSyncEvent> =
        eventDao.getPendingSync().map { PendingSyncEvent(it.toDomain(), it.isDeleted) }

    override suspend fun markSynced(id: String, syncBase: SyncSnapshot) =
        eventDao.markSynced(id, syncBase.toJson())

    override suspend fun setRemoteId(id: String, remoteId: String) =
        eventDao.setRemoteId(id, remoteId)

    override suspend fun attachToProvider(
        id: String,
        remoteId: String,
        provider: CalendarProvider,
        syncBase: SyncSnapshot?,
    ) = eventDao.attachToProvider(id, remoteId, provider.name.lowercase(), syncBase?.toJson())

    override suspend fun findSyncedRecord(provider: CalendarProvider, remoteId: String): SyncedRecord? =
        eventDao.findByRemoteId(provider.name.lowercase(), remoteId)?.let {
            SyncedRecord(
                event = it.toDomain(),
                isDeleted = it.isDeleted,
                pendingSync = it.pendingSync,
                base = SyncSnapshot.fromJson(it.syncBase),
            )
        }

    override suspend fun saveResolved(event: Event, syncBase: SyncSnapshot) {
        // Выбранное пользователем состояние и есть новый предок: следующий pull
        // обязан считать локальными изменениями только то, что появится после разбора.
        eventDao.upsert(
            event.toEntity(
                updatedAt = System.currentTimeMillis(),
                pendingSync = true,
                syncBase = syncBase.toJson(),
            )
        )
    }

    override suspend fun purge(id: String) {
        eventDao.getEvent(id)?.voiceNotePath?.let(voiceNotes::deleteFile)
        eventDao.hardDelete(id)
    }
}
