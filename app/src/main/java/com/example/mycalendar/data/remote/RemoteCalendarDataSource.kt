package com.example.mycalendar.data.remote

import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule

/**
 * Событие в нормализованном виде удалённого провайдера.
 * Служит "буфером" между REST API (Google/Yandex) и локальной БД.
 */
data class RemoteEvent(
    val remoteId: String,
    val title: String,
    val description: String?,
    val startTime: Long,
    val endTime: Long,
    val reminderMinutes: List<Int>,
    val repeatRule: RepeatRule?,
    /** updated (RFC3339, пересчёт в миллисекунды от эпохи): отметка времени изменения на стороне
     *  провайдера; в решении о слиянии не участвует, читателей в движке нет. */
    val updatedAt: Long,
    /** status=cancelled в Google Calendar означает удаление. */
    val deleted: Boolean = false,
)

/**
 * Результат pull-запроса.
 *
 * [presentRemoteIds] — все remoteId, реально найденные провайдером в коллекции
 * (независимо от `since`), либо null, если провайдер не перечисляет коллекцию целиком.
 * CalDAV не присылает tombstone'ов: событие, удалённое в облаке, просто исчезает из
 * выборки, поэтому движок синхронизации распознаёт удаление по отсутствию id —
 * но только когда список присутствующих полон и ему можно верить.
 */
data class RemoteFetch(
    val events: List<RemoteEvent>,
    val presentRemoteIds: Set<String>? = null,
    /**
     * URL коллекции, из которой получена выборка (CalDAV). По нему движок отличает ту же
     * коллекцию от «чужой»: пустая выборка из первого же запроса после смены аккаунта
     * не должна вычищать все локальные привязки.
     */
    val collectionUrl: String? = null,
)

/**
 * Абстракция удалённого календаря: pull-запросы изменённых событий и push-операции.
 * Реализации: SystemCalendarDataSource (Google через системный календарь
 * Android, CalendarContract), YandexCalendarDataSource (REST).
 */
interface RemoteCalendarDataSource {

    /** События, изменённые с момента [sinceMillis] (0 = вся история), и состав коллекции. */
    suspend fun fetchUpdatedSince(sinceMillis: Long): RemoteFetch

    /** Создание события; возвращает assigned remoteId. */
    suspend fun insert(event: Event): String

    /** Обновление события по remoteId. */
    suspend fun update(remoteId: String, event: Event)

    /** Удаление события по remoteId. */
    suspend fun delete(remoteId: String)
}

/** Маппер удалённого события в локальное (новый локальный id, voice/text заметки пустые). */
fun RemoteEvent.toEvent(provider: com.example.mycalendar.domain.model.CalendarProvider): Event =
    Event(
        id = java.util.UUID.randomUUID().toString(),
        title = title,
        description = description,
        startTime = startTime,
        endTime = endTime,
        reminderMinutes = reminderMinutes,
        repeatRule = repeatRule,
        textNote = null,
        voiceNotePath = null,
        calendarProvider = provider,
        remoteId = remoteId,
        updatedAt = updatedAt,
    )
