package com.example.mycalendar.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.SyncSnapshot

/**
 * Открытый конфликт синхронизации: одна строка на событие (уникальный индекс по eventId).
 *
 * Локальную версию НЕ храним — разбираем конфликт против текущего значения строки,
 * иначе правка события во время открытого конфликта осталась бы «проигравшей».
 */
@Entity(
    tableName = "sync_conflicts",
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["eventId"], unique = true)],
)
data class SyncConflictEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    /** Имя [CalendarProvider]: google / yandex. */
    val provider: String,
    val remoteId: String,
    /** Имя [ConflictKind]. */
    val kind: String,
    /** JSON [SyncSnapshot] предка; null — строка до миграции. */
    val base: String?,
    /** JSON [SyncSnapshot] облачной версии. */
    val remote: String,
    /** Имена [SyncField] через запятую; пусто для конфликтов-удалений. */
    val contestedFields: String,
    val detectedAt: Long,
)

private val emptySnapshot = SyncSnapshot(title = "", startTime = 0L, endTime = 0L)

/**
 * Сборка доменного конфликта из строки таблицы.
 *
 * Маппинг толерантный: строку могла записать другая версия приложения, а показать
 * пользователю конфликт, которого на самом деле нет, хуже, чем ошибиться в детали
 * разбора. Четыре сознательные подмены при нераспознанных данных: неизвестное имя
 * провайдера становится `LOCAL`, неизвестный вид конфликта — `CONTENT`, битые имена
 * спорных полей просто
 * отбрасываются, а непарсящееся облачное значение заменяется пустым снимком. Логи
 * здесь не пишутся: в этом слое нет инжектируемого логгера, а молчаливая подмена всё
 * равно остаётся совместной.
 */
fun SyncConflictEntity.toDomain(): SyncConflict = SyncConflict(
    id = id,
    eventId = eventId,
    provider = runCatching { CalendarProvider.valueOf(provider.uppercase()) }
        .getOrDefault(CalendarProvider.LOCAL),
    remoteId = remoteId,
    kind = runCatching { ConflictKind.valueOf(kind) }.getOrDefault(ConflictKind.CONTENT),
    base = SyncSnapshot.fromJson(base),
    remote = SyncSnapshot.fromJson(remote) ?: emptySnapshot,
    contested = if (contestedFields.isBlank()) emptySet()
    else contestedFields.split(",").mapNotNull { name -> runCatching { SyncField.valueOf(name) }.getOrNull() }.toSet(),
    detectedAt = detectedAt,
)

fun SyncConflict.toEntity(): SyncConflictEntity = SyncConflictEntity(
    id = id,
    eventId = eventId,
    provider = provider.name.lowercase(),
    remoteId = remoteId,
    kind = kind.name,
    base = base?.toJson(),
    remote = remote.toJson(),
    contestedFields = contested.joinToString(","),
    detectedAt = detectedAt,
)
