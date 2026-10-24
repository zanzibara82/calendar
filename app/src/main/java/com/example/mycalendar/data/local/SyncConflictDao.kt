package com.example.mycalendar.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncConflictDao {

    @Query("SELECT * FROM sync_conflicts ORDER BY detectedAt ASC")
    fun observeAll(): Flow<List<SyncConflictEntity>>

    @Query("SELECT COUNT(*) FROM sync_conflicts")
    fun observeCount(): Flow<Int>

    /** События с открытым конфликтом — их нельзя отправлять в облако до разбора. */
    @Query("SELECT eventId FROM sync_conflicts")
    suspend fun openEventIds(): List<String>

    @Query("SELECT * FROM sync_conflicts WHERE id = :id")
    suspend fun getById(id: String): SyncConflictEntity?

    @Query("SELECT id FROM sync_conflicts WHERE eventId = :eventId LIMIT 1")
    suspend fun idByEvent(eventId: String): String?

    @Upsert
    suspend fun upsert(conflict: SyncConflictEntity)

    @Query("DELETE FROM sync_conflicts WHERE id = :id")
    suspend fun deleteById(id: String)
}
