package com.example.mycalendar.data.repository

import com.example.mycalendar.data.local.SyncConflictDao
import com.example.mycalendar.data.local.toDomain
import com.example.mycalendar.data.local.toEntity
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.repository.ConflictRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** [ConflictRepository] поверх Room; один открытый конфликт на событие обеспечивает уникальный индекс. */
@Singleton
class ConflictRepositoryImpl @Inject constructor(
    private val conflictDao: SyncConflictDao,
) : ConflictRepository {

    override fun observeOpenCount(): Flow<Int> = conflictDao.observeCount()

    override fun observeConflicts(): Flow<List<SyncConflict>> =
        conflictDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): SyncConflict? = conflictDao.getById(id)?.toDomain()

    override suspend fun save(conflict: SyncConflict) {
        // Повторное обнаружение держит и идентификатор (на него уже открыт экран разбора), и
        // время первого обнаружения (по нему сортируется очередь). Обновляются только снимки.
        val existing = conflictDao.getByEvent(conflict.eventId)
        conflictDao.upsert(
            conflict.copy(
                id = existing?.id ?: conflict.id,
                detectedAt = existing?.detectedAt ?: conflict.detectedAt,
            ).toEntity()
        )
    }

    override suspend fun delete(id: String) = conflictDao.deleteById(id)

    override suspend fun openEventIds(): List<String> = conflictDao.openEventIds()
}
