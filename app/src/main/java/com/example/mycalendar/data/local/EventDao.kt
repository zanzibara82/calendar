package com.example.mycalendar.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    /**
     * События для Month/Week/Day видов.
     * Одиночные события фильтруются по пересечению с диапазоном,
     * повторяющиеся (мастер-записи) берутся целиком — точный отбор вхождений
     * выполняется в RepeatRuleHelper на уровне домена.
     */
    @Query(
        """
        SELECT * FROM events
        WHERE isDeleted = 0
          AND startTime <= :to
          AND (repeatRule IS NOT NULL OR endTime >= :from)
        ORDER BY startTime ASC
        """
    )
    fun observeEventsBetween(from: Long, to: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE id = :id")
    fun observeEvent(id: String): Flow<EventEntity?>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getEvent(id: String): EventEntity?

    /** Для перепланирования напоминаний: одиночные будущие + все повторяющиеся. */
    @Query(
        """
        SELECT * FROM events
        WHERE isDeleted = 0 AND (startTime >= :now OR repeatRule IS NOT NULL)
        ORDER BY startTime ASC
        LIMIT :limit
        """
    )
    suspend fun getUpcoming(now: Long, limit: Int): List<EventEntity>

    @Upsert
    suspend fun upsert(event: EventEntity)

    /** Tombstone: событие удалено локально, требуется удалить и в облаке. */
    @Query("UPDATE events SET isDeleted = 1, pendingSync = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: String, updatedAt: Long)

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun hardDelete(id: String)

    /** Все записи, ожидающие отправки в облако (включая tombstone'ы). */
    @Query("SELECT * FROM events WHERE pendingSync = 1")
    suspend fun getPendingSync(): List<EventEntity>

    /** Служебное состояние строки: предок и время изменения — чтобы не вычитывать весь контент. */
    @Query("SELECT syncBase, updatedAt FROM events WHERE id = :id")
    suspend fun getSyncState(id: String): EventSyncState?

    /**
     * Успешный push: предок догоняет отправленное состояние.
     * Больше никаких мест, где syncBase двигает синхронизация, нет (разбор — Task 7).
     */
    @Query("UPDATE events SET pendingSync = 0, syncBase = :syncBase WHERE id = :id")
    suspend fun markSynced(id: String, syncBase: String?)

    /** Привязка к провайдеру: событие уходит в облако и больше не считается локальным. */
    @Query(
        "UPDATE events SET remoteId = :remoteId, calendarProvider = :provider, " +
            "pendingSync = 0, syncBase = :syncBase WHERE id = :id"
    )
    suspend fun attachToProvider(id: String, remoteId: String, provider: String, syncBase: String?)

    @Query("SELECT * FROM events WHERE calendarProvider = :provider AND remoteId = :remoteId LIMIT 1")
    suspend fun findByRemoteId(provider: String, remoteId: String): EventEntity?
}

/** Две служебные колонки одной строки (запрос [EventDao.getSyncState]). */
data class EventSyncState(val syncBase: String?, val updatedAt: Long)
