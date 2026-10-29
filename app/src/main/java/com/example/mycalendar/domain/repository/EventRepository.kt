package com.example.mycalendar.domain.repository

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.SyncedRecord
import com.example.mycalendar.domain.model.SyncSnapshot
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
     * Запись, приехавшая ИЗ облака: pendingSync не выставляется (иначе цикл синхронизации),
     * а [syncBase] становится новым снимком-предком этой строки.
     */
    suspend fun saveFromRemote(event: Event, syncBase: SyncSnapshot?)

    /** Мягкое удаление: запись помечается tombstone'ом и удаляется из облака при синхронизации. */
    suspend fun markDeleted(id: String)

    /** Физическое удаление записи (после успешного удаления в облаке). */
    suspend fun hardDelete(id: String)

    /** События, ожидающие отправки в облако (в т.ч. tombstone'ы). */
    suspend fun getPendingSyncEvents(): List<PendingSyncEvent>

    /** Успешный push: снять pendingSync и догнать предка отправленным состоянием. */
    suspend fun markSynced(id: String, syncBase: SyncSnapshot)

    /**
     * Сдвинуть снимок-предок, не трогая содержимое, отметку удаления и флаг отправки.
     * Разбор «удалить везде» при локальном удалении: правка, из-за которой возник конфликт,
     * становится предком — следующий pull увидит, что облако относительно предка не менялось,
     * и отправит tombstone на удаление вместо нового конфликта.
     */
    suspend fun moveSyncBase(id: String, syncBase: SyncSnapshot)

    /**
     * Привязка локального события к провайдеру после создания его в облаке:
     * сохраняет [remoteId], выставляет [provider] и снимает флаг pendingSync.
     *
     * Выставление провайдера важно при НЕСКОЛЬКИХ подключённых календарях:
     * иначе событие осталось бы LOCAL и попало бы в push второго календаря.
     */
    suspend fun attachToProvider(
        id: String,
        remoteId: String,
        provider: CalendarProvider,
        syncBase: SyncSnapshot?,
    )

    /** Строка с её служебным состоянием (для решения о слиянии) или null, если пары нет. */
    suspend fun findSyncedRecord(provider: CalendarProvider, remoteId: String): SyncedRecord?

    /**
     * Все строки, привязанные к календарю [provider] (remoteId не пуст).
     * Нужны для детекта удалений по отсутствию события в полной облачной выборке.
     */
    suspend fun getAttachedRecords(provider: CalendarProvider): List<SyncedRecord>

    /**
     * Разбор конфликта: [event] с выбранными значениями становится локальной записью,
     * а [syncBase] — новым предком. Разобранное состояние ещё не в облаке,
     * поэтому флаг неотправленного включается обязательно.
     */
    suspend fun saveResolved(event: Event, syncBase: SyncSnapshot)

    /**
     * Полное удаление: сначала файл голосовой заметки, затем строка БД.
     *
     * Гарантии «файла нет ровно тогда, когда нет строки» контракт не даёт: общего
     * транзакционного журнала между файловой системой и Room нет, поэтому атомарность
     * между ними недостижима. При отказе файловой системы удаление фиксируется
     * предупреждением в логе data-слоя, а строка удаляется в любом случае.
     */
    suspend fun purge(id: String)
}
