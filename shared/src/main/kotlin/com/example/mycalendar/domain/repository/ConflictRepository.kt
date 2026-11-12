package com.example.mycalendar.domain.repository

import com.example.mycalendar.domain.model.SyncConflict
import kotlinx.coroutines.flow.Flow

/**
 * Очередь открытых конфликтов синхронизации (§7 ТЗ «не перезаписывай молча»).
 * Не больше одного конфликта на событие; разбирается на экране конфликтов.
 */
interface ConflictRepository {

    fun observeOpenCount(): Flow<Int>

    fun observeConflicts(): Flow<List<SyncConflict>>

    suspend fun get(id: String): SyncConflict?

    /** Сохранить обнаруженный конфликт: для уже открытого события перезаписать существующий. */
    suspend fun save(conflict: SyncConflict)

    suspend fun delete(id: String)

    /** События с открытым конфликтом — их не отправляем в облако до разбора. */
    suspend fun openEventIds(): List<String>
}
