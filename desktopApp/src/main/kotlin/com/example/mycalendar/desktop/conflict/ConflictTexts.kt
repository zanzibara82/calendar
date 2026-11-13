package com.example.mycalendar.desktop.conflict

import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Тексты разбора конфликтов: формулировки повторяют Android, формат дат — общий русский
 * интерфейс (`d MMMM yyyy, HH:mm` в значениях полей и `d MMM yyyy, HH:mm` в очереди).
 */
private val russianLocale: Locale = Locale.forLanguageTag("ru")

private val fullDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", russianLocale)

private val shortDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", russianLocale)

private const val REMOTE_DELETED_TEXT = "Событие удалено в облаке, локально есть ваши правки"
private const val LOCAL_DELETED_TEXT = "Событие удалено локально, а в облаке есть версия"

/** Склонения по русским правилам, а не по локали: интерфейс всегда русский. */
internal fun contestedFieldsLabel(count: Int): String = when {
    count % 10 == 1 && count % 100 != 11 -> "$count спорное поле"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "$count спорных поля"
    else -> "$count спорных полей"
}

/** Строка очереди: что столкнулось и сколько полей надо разобрать. */
internal fun conflictSummary(conflict: SyncConflict): String = when (conflict.kind) {
    ConflictKind.CONTENT -> contestedFieldsLabel(conflict.contested.size)
    ConflictKind.REMOTE_DELETED -> REMOTE_DELETED_TEXT
    ConflictKind.LOCAL_DELETED -> LOCAL_DELETED_TEXT
}

internal fun conflictHelp(kind: ConflictKind): String = when (kind) {
    ConflictKind.CONTENT ->
        "Выберите, какое значение оставить; непересекающиеся правки приложение объединило само"
    ConflictKind.REMOTE_DELETED -> REMOTE_DELETED_TEXT
    ConflictKind.LOCAL_DELETED -> LOCAL_DELETED_TEXT
}

internal fun fieldLabel(field: SyncField): String = when (field) {
    SyncField.TITLE -> "Название"
    SyncField.DESCRIPTION -> "Описание"
    SyncField.START_TIME -> "Начало"
    SyncField.END_TIME -> "Конец"
    SyncField.REMINDER_MINUTES -> "Напоминания"
    SyncField.REPEAT_RULE -> "Повтор"
}

internal fun localValue(field: SyncField, event: Event, zone: ZoneId): String = when (field) {
    SyncField.TITLE -> displayTitle(event.title)
    SyncField.DESCRIPTION -> displayDescription(event.description)
    SyncField.START_TIME -> dateTime(event.startTime, zone)
    SyncField.END_TIME -> dateTime(event.endTime, zone)
    SyncField.REMINDER_MINUTES -> remindersLabel(event.reminderMinutes)
    SyncField.REPEAT_RULE -> repeatLabel(event.repeatRule)
}

internal fun cloudValue(field: SyncField, conflict: SyncConflict, zone: ZoneId): String = when (field) {
    SyncField.TITLE -> displayTitle(conflict.remote.title)
    SyncField.DESCRIPTION -> displayDescription(conflict.remote.description)
    SyncField.START_TIME -> dateTime(conflict.remote.startTime, zone)
    SyncField.END_TIME -> dateTime(conflict.remote.endTime, zone)
    SyncField.REMINDER_MINUTES -> remindersLabel(conflict.remote.reminderMinutes)
    SyncField.REPEAT_RULE -> repeatLabel(conflict.remote.repeatRule)
}

internal fun displayTitle(title: String): String =
    title.ifBlank { "Событие без названия" }

internal fun displayDescription(description: String?): String =
    description?.takeIf { it.isNotBlank() } ?: "без описания"

internal fun repeatLabel(rule: RepeatRule?): String = when (rule) {
    null -> "Без повтора"
    RepeatRule.DAILY -> "Каждый день"
    RepeatRule.WEEKLY -> "Каждую неделю"
    RepeatRule.MONTHLY -> "Каждый месяц"
    RepeatRule.YEARLY -> "Каждый год"
}

internal fun remindersLabel(minutes: List<Int>): String {
    if (minutes.isEmpty()) return "без напоминаний"
    return minutes.sorted().joinToString(", ") { "$it мин" }
}

internal fun dateTime(millis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(millis).atZone(zone).format(fullDateTimeFormatter)

/**
 * Время обнаружения в очереди — короткий формат, как в списке конфликтов Android.
 * CLDR на JVM сокращает русские месяцы с точкой («мар.»), а Android — без неё; других
 * точек в формате нет, поэтому точка убирается целиком.
 */
internal fun detectedAtText(millis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(millis).atZone(zone).format(shortDateTimeFormatter).replace(".", "")

internal fun noticeText(notice: ConflictsController.Notice): String = when (notice) {
    ConflictsController.Notice.STALE -> "Конфликт изменился — откройте его заново из очереди"
    ConflictsController.Notice.NO_LOCAL_ROW -> "Конфликт больше не актуален"
}
