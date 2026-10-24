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
    /** updated (RFC3339 -> epoch millis): основа для last-write-wins. */
    val updatedAt: Long,
    /** status=cancelled в Google Calendar означает удаление. */
    val deleted: Boolean = false,
)

/**
 * Абстракция удалённого календаря: pull-запросы изменённых событий и push-операции.
 * Реализации: SystemCalendarDataSource (Google через системный календарь
 * Android, CalendarContract), YandexCalendarDataSource (REST).
 */
interface RemoteCalendarDataSource {

    /**
     * Есть ли у провайдера достоверное время изменения события.
     *
     * У системного календаря Android ([CalendarContract]) такой колонки нет, поэтому
     * инкрементальный pull по updatedMin невозможен и каждая синхронизация читает всю
     * выборку. На решение о слиянии признак больше не влияет: сравнивается снимок-предок,
     * а не время изменения.
     */
    val hasReliableUpdatedAt: Boolean get() = true

    /** Все события, изменённые с момента [sinceMillis] (0 = вся история). */
    suspend fun fetchUpdatedSince(sinceMillis: Long): List<RemoteEvent>

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
