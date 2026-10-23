package com.example.mycalendar.data.repository

import com.example.mycalendar.data.local.EventDao
import com.example.mycalendar.data.local.toDomain
import com.example.mycalendar.data.local.toEntity
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.domain.repository.PendingSyncEvent
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
        eventDao.upsert(
            event.toEntity(
                updatedAt = System.currentTimeMillis(),
                pendingSync = true,
            )
        )
    }

    override suspend fun saveFromRemote(event: Event) {
        eventDao.upsert(
            event.toEntity(
                updatedAt = event.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                pendingSync = false,
            )
        )
    }

    override suspend fun markDeleted(id: String) {
        eventDao.softDelete(id, System.currentTimeMillis())
    }

    override suspend fun hardDelete(id: String) = eventDao.hardDelete(id)

    override suspend fun getPendingSyncEvents(): List<PendingSyncEvent> =
        eventDao.getPendingSync().map { PendingSyncEvent(it.toDomain(), it.isDeleted) }

    override suspend fun markSynced(ids: List<String>) {
        if (ids.isNotEmpty()) eventDao.markSynced(ids)
    }

    override suspend fun markPendingSync(id: String) = eventDao.markPendingSync(id)

    override suspend fun setRemoteId(id: String, remoteId: String) =
        eventDao.setRemoteId(id, remoteId)

    override suspend fun attachToProvider(id: String, remoteId: String, provider: CalendarProvider) =
        eventDao.attachToProvider(id, remoteId, provider.name.lowercase())

    override suspend fun findByRemoteId(provider: CalendarProvider, remoteId: String): Event? =
        eventDao.findByRemoteId(provider.name.lowercase(), remoteId)?.toDomain()
}
