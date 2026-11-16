package com.example.mycalendar.data.remote.google

import com.example.mycalendar.data.remote.RemoteEvent
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.util.RruleCodec
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
data class GoogleEventDto(
    val id: String? = null,
    val status: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val start: GoogleDateTimeDto? = null,
    val end: GoogleDateTimeDto? = null,
    val recurrence: List<String>? = null,
    val reminders: GoogleRemindersDto? = null,
    val updated: String? = null,
)

@Serializable
data class GoogleDateTimeDto(
    val dateTime: String? = null,
    val date: String? = null,
    val timeZone: String? = null,
)

@Serializable
data class GoogleRemindersDto(
    val useDefault: Boolean? = null,
    val overrides: List<GoogleReminderDto>? = null,
)

@Serializable
data class GoogleReminderDto(
    val method: String? = null,
    val minutes: Int? = null,
)

@Serializable
data class GoogleEventsPageDto(
    val items: List<GoogleEventDto> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class GoogleDateTimeWriteDto(
    val dateTime: String,
    val timeZone: String,
)

/**
 * Тело записи: только редактируемые поля. `id`, `status` и `updated` Google
 * выставляет сам; в PATCH они не отправляются. `description = null` очищает
 * описание, `recurrence = null` снимает повтор — это осознанная перезапись.
 */
@Serializable
data class GoogleEventWriteDto(
    val summary: String,
    val description: String? = null,
    val start: GoogleDateTimeWriteDto,
    val end: GoogleDateTimeWriteDto,
    val recurrence: List<String>? = null,
    val reminders: GoogleRemindersDto,
)

/**
 * Отображение событий Google Calendar API v3 в домен и обратно.
 *
 * All-day события Google приходят датами: `start.date` включительно, `end.date`
 * исключительно. В домен они попадают как местные сутки: 00:00 старт и
 * 23:59:59.999 конец. Напоминания — только `popup` (email домен не моделирует).
 */
object GoogleEventMapper {

    const val NO_TITLE = "(без названия)"
    private const val DEFAULT_DURATION_MILLIS = 3_600_000L

    fun fromDto(dto: GoogleEventDto, zone: ZoneId, observedAt: Long): RemoteEvent? {
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return null
        val start = startMillis(dto.start, zone) ?: 0L
        val end = endMillis(dto.end, zone) ?: (start + DEFAULT_DURATION_MILLIS)
        return RemoteEvent(
            remoteId = id,
            title = dto.summary?.takeIf { it.isNotBlank() } ?: NO_TITLE,
            description = dto.description,
            startTime = start,
            endTime = end,
            reminderMinutes = popupMinutes(dto),
            repeatRule = RruleCodec.fromRrule(
                dto.recurrence?.firstOrNull { it.startsWith("RRULE", ignoreCase = true) }
            ),
            updatedAt = dto.updated
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: observedAt,
            deleted = dto.status == "cancelled",
        )
    }

    fun toWriteDto(event: Event, zone: ZoneId): GoogleEventWriteDto = GoogleEventWriteDto(
        summary = event.title,
        description = event.description,
        start = GoogleDateTimeWriteDto(dateTime = rfc3339(event.startTime, zone), timeZone = zone.id),
        end = GoogleDateTimeWriteDto(dateTime = rfc3339(event.endTime, zone), timeZone = zone.id),
        recurrence = RruleCodec.toRrule(event.repeatRule)?.let { listOf("RRULE:$it") },
        reminders = GoogleRemindersDto(
            useDefault = false,
            overrides = event.reminderMinutes
                .distinct()
                .filter { it >= 0 }
                .sorted()
                .map { GoogleReminderDto(method = "popup", minutes = it) },
        ),
    )

    private fun popupMinutes(dto: GoogleEventDto): List<Int> =
        dto.reminders?.overrides.orEmpty()
            .filter { it.method == "popup" }
            .mapNotNull { it.minutes?.takeIf { minutes -> minutes >= 0 } }
            .distinct()
            .sorted()

    private fun startMillis(value: GoogleDateTimeDto?, zone: ZoneId): Long? {
        if (value == null) return null
        value.dateTime?.let(::dateTimeMillis)?.let { return it }
        return value.date?.let { date ->
            runCatching {
                LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
            }.getOrNull()
        }
    }

    private fun endMillis(value: GoogleDateTimeDto?, zone: ZoneId): Long? {
        if (value == null) return null
        value.dateTime?.let(::dateTimeMillis)?.let { return it }
        return value.date?.let { date ->
            runCatching {
                LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            }.getOrNull()
        }
    }

    private fun dateTimeMillis(raw: String): Long? =
        runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()

    private fun rfc3339(epochMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toOffsetDateTime().toString()
}
