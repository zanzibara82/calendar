package com.example.mycalendar.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Синхронизируемое поле события — единица спора при конфликте. */
enum class SyncField {
    TITLE,
    DESCRIPTION,
    START_TIME,
    END_TIME,
    REMINDER_MINUTES,
    REPEAT_RULE,
}

/**
 * Снимок синхронизируемых полей — «предок»: общее состояние локальной записи
 * и облака на момент последней успешной синхронизации.
 *
 * Метки, текстовая и голосовая заметки в снимок НЕ входят принципиально: облако
 * их не хранит и не возвращает, поэтому спорными они быть не могут (см. Event.appliedTo).
 */
@Serializable
data class SyncSnapshot(
    val title: String,
    val description: String? = null,
    val startTime: Long,
    val endTime: Long,
    val reminderMinutes: List<Int> = emptyList(),
    val repeatRule: RepeatRule? = null,
) {
    fun toJson(): String = json.encodeToString(this)

    /**
     * Значение одного синхронизируемого поля в сравнительном виде: порядок минут
     * не значим, пустое и отсутствующее описание — одно и то же.
     */
    fun fieldOf(field: SyncField): Any? = when (field) {
        SyncField.TITLE -> title
        SyncField.DESCRIPTION -> description.orEmpty()
        SyncField.START_TIME -> startTime
        SyncField.END_TIME -> endTime
        SyncField.REMINDER_MINUTES -> reminderMinutes.toSet()
        SyncField.REPEAT_RULE -> repeatRule
    }

    /**
     * [event] с значениями полей [fields] из снимка; остальное остаётся локальным.
     *
     * Поле DESCRIPTION налагается буквально: [SyncSnapshot.description] переносится как
     * есть, поэтому снимок с description = null ОЧИЩАЕТ описание события. Это расходится
     * с [fieldOf], где null и пустая строка — одно и то же: при сравнении отсутствие
     * описания не считается отличием, а при наложении выбранного поля DESCRIPTION оно
     * стирает локальное значение. Слияние из синхронизации накладывает ровно выбранные
     * поля, так что это поведение зафиксировано тестом.
     */
    fun appliedTo(event: Event, fields: Set<SyncField>): Event = fields.fold(event) { acc, field ->
        when (field) {
            SyncField.TITLE -> acc.copy(title = title)
            SyncField.DESCRIPTION -> acc.copy(description = description)
            SyncField.START_TIME -> acc.copy(startTime = startTime)
            SyncField.END_TIME -> acc.copy(endTime = endTime)
            SyncField.REMINDER_MINUTES -> acc.copy(reminderMinutes = reminderMinutes)
            SyncField.REPEAT_RULE -> acc.copy(repeatRule = repeatRule)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** null — строка до миграции либо событие, которое ещё ни с чем не синхронизировалось. */
        fun fromJson(raw: String?): SyncSnapshot? = try {
            raw?.let { json.decodeFromString<SyncSnapshot>(it) }
        } catch (e: SerializationException) {
            null
        }

        fun of(event: Event): SyncSnapshot = SyncSnapshot(
            title = event.title,
            description = event.description,
            startTime = event.startTime,
            endTime = event.endTime,
            reminderMinutes = event.reminderMinutes,
            repeatRule = event.repeatRule,
        )
    }
}

/**
 * Поля, в которых снимки различаются. Сравнение идёт по [SyncSnapshot.fieldOf]:
 * напоминания — как МНОЖЕСТВО (порядок минут ничего не значит), описание — без
 * различия null/пусто. Тот же примитив фильтрует спорные поля при слиянии.
 */
fun diffFields(base: SyncSnapshot, other: SyncSnapshot): Set<SyncField> =
    SyncField.entries.filter { base.fieldOf(it) != other.fieldOf(it) }.toSet()
