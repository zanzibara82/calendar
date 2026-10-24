package com.example.mycalendar.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.EventTag
import com.example.mycalendar.domain.model.RepeatRule

/**
 * Room-сущность события. Является единственной таблицей локальной БД.
 *
 * Дополнительные служебные поля для двусторонней синхронизации:
 *  - isDeleted   — tombstone: событие удалено локально, но ещё не удалено в облаке;
 *  - pendingSync — локальное изменение, которое нужно отправить в облако.
 */
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val startTime: Long,
    val endTime: Long,
    /** Список минут до события, сериализованный как "5,15,30" (см. [toDomain]/[fromDomain]). */
    val reminderMinutes: String,
    /** Имя [RepeatRule] или null для одиночных событий. */
    val repeatRule: String?,
    val textNote: String?,
    /** Метки события: имена [EventTag] через запятую ("BIRTHDAY,CINEMA"). */
    val tags: String,
    /** Абсолютный путь к голосовой заметке (M4A) — файл остаётся только на устройстве. */
    val voiceNotePath: String?,
    /** Имя [CalendarProvider]: local / google / yandex. */
    val calendarProvider: String,
    val remoteId: String?,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val pendingSync: Boolean = true,
    /** JSON [com.example.mycalendar.domain.model.SyncSnapshot]: общее состояние с облаком. */
    val syncBase: String? = null,
)

/** Сериализация списка напоминаний: [5, 15, 30] -> "5,15,30". */
private fun List<Int>.serialize(): String = joinToString(",")

private fun String.deserializeReminders(): List<Int> =
    if (isBlank()) emptyList() else split(",").mapNotNull { it.trim().toIntOrNull() }

/** Сериализация меток: [BIRTHDAY, CINEMA] -> "BIRTHDAY,CINEMA". */
private fun List<EventTag>.serializeTags(): String = joinToString(",")

private fun String.deserializeTags(): List<EventTag> =
    if (isBlank()) emptyList() else split(",").mapNotNull {
        runCatching { EventTag.valueOf(it.trim()) }.getOrNull()
    }

/** Доменная модель <- entity. */
fun EventEntity.toDomain(): Event = Event(
    id = id,
    title = title,
    description = description,
    startTime = startTime,
    endTime = endTime,
    reminderMinutes = reminderMinutes.deserializeReminders(),
    repeatRule = repeatRule?.let { runCatching { RepeatRule.valueOf(it) }.getOrNull() },
    textNote = textNote,
    tags = tags.deserializeTags(),
    voiceNotePath = voiceNotePath,
    calendarProvider = runCatching { CalendarProvider.valueOf(calendarProvider.uppercase()) }
        .getOrDefault(CalendarProvider.LOCAL),
    remoteId = remoteId,
    updatedAt = updatedAt,
)

/** Entity <- доменная модель. */
fun Event.toEntity(
    updatedAt: Long,
    pendingSync: Boolean,
    isDeleted: Boolean = false,
    syncBase: String? = null,
): EventEntity = EventEntity(
    id = id,
    title = title,
    description = description,
    startTime = startTime,
    endTime = endTime,
    reminderMinutes = reminderMinutes.serialize(),
    repeatRule = repeatRule?.name,
    textNote = textNote,
    tags = tags.serializeTags(),
    voiceNotePath = voiceNotePath,
    calendarProvider = calendarProvider.name.lowercase(),
    remoteId = remoteId,
    updatedAt = updatedAt,
    isDeleted = isDeleted,
    pendingSync = pendingSync,
    syncBase = syncBase,
)
