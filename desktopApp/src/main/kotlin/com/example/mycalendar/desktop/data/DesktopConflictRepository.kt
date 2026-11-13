package com.example.mycalendar.desktop.data

import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.repository.ConflictRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * [ConflictRepository] поверх conflicts.json; семантика повторяет Room-реализацию Android:
 * не больше одного конфликта на событие, очередь упорядочена по времени первого обнаружения.
 */
class DesktopConflictRepository(
    private val store: DesktopConflictStore,
) : ConflictRepository {

    override fun observeOpenCount(): Flow<Int> = observeConflicts().map { it.size }

    override fun observeConflicts(): Flow<List<SyncConflict>> = flow {
        emit(store.snapshot())
        emitAll(store.conflicts.filterNotNull())
    }.map { conflicts -> conflicts.sortedBy { it.detectedAt } }

    override suspend fun get(id: String): SyncConflict? =
        store.snapshot().firstOrNull { it.id == id }

    override suspend fun save(conflict: SyncConflict) {
        // Повторное обнаружение держит идентификатор (на него уже открыт экран разбора)
        // и время первого обнаружения (по нему сортируется очередь).
        store.update { conflicts ->
            val existing = conflicts.firstOrNull { it.eventId == conflict.eventId }
            val next = conflict.copy(
                id = existing?.id ?: conflict.id,
                detectedAt = existing?.detectedAt ?: conflict.detectedAt,
            )
            if (existing == null) {
                conflicts + next
            } else {
                conflicts.map { if (it.eventId == conflict.eventId) next else it }
            }
        }
    }

    override suspend fun delete(id: String) {
        store.update { conflicts -> conflicts.filterNot { it.id == id } }
    }

    override suspend fun openEventIds(): List<String> =
        store.snapshot().map { it.eventId }
}
