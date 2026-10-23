package com.example.mycalendar.domain.repository

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import kotlinx.coroutines.flow.Flow

/**
 * Событие, ожидающее отправки в облако.
 * isDeleted = true означает tombstone: на удалённой стороне нужно удалить remoteId.
 */
data class PendingSyncEvent(
    val event: Event,
    val isDeleted: Boolean,
)

/**
 * Контракт работы с событиями. Реализация — в data-слое (Room + синхронизация).
 */
interface EventRepository {

    /** События, пересекающиеся с диапазоном [from, to] (для Month/Week/Day видов). */
    fun observeEventsBetween(from: Long, to: Long): Flow<List<Event>>

    fun observeEvent(id: String): Flow<Event?>

    suspend fun getEvent(id: String): Event?

    /** Будущие события (tombstone'ы не учитываются). */
    suspend fun getUpcomingEvents(now: Long, limit: Int = 500): List<Event>

    /**
     * Создание или локальное изменение события пользователем:
     * выставляет updatedAt = now и pendingSync = true для последующей отправки в облако.
     */
    suspend fun saveFromUser(event: Event)

    /**
     * Запись события, пришедшего ИЗ облака: pendingSync не выставляется,
     * updatedAt берётся из удалённого источника (важно для last-write-wins).
     */
    suspend fun saveFromRemote(event: Event)

    /** Мягкое удаление: запись помечается tombstone'ом и удаляется из облака при синхронизации. */
    suspend fun markDeleted(id: String)

    /** Физическое удаление записи (после успешного удаления в облаке). */
    suspend fun hardDelete(id: String)

    /** События, ожидающие отправки в облако (в т.ч. tombstone'ы). */
    suspend fun getPendingSyncEvents(): List<PendingSyncEvent>

    suspend fun markSynced(ids: List<String>)

    /** Пометить событие требующим отправки в облако (updatedAt не меняется). */
    suspend fun markPendingSync(id: String)

    suspend fun setRemoteId(id: String, remoteId: String)

    /**
     * Привязка локального события к провайдеру после создания его в облаке:
     * сохраняет [remoteId], выставляет [provider] и снимает флаг pendingSync.
     *
     * Выставление провайдера важно при НЕСКОЛЬКИХ подключённых календарях:
     * иначе событие осталось бы LOCAL и попало бы в push второго календаря.
     */
    suspend fun attachToProvider(id: String, remoteId: String, provider: CalendarProvider)

    suspend fun findByRemoteId(provider: CalendarProvider, remoteId: String): Event?
}
