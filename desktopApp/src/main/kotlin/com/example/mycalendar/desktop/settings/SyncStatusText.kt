package com.example.mycalendar.desktop.settings

import com.example.mycalendar.domain.repository.SyncResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val lastSyncFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.forLanguageTag("ru"))

/**
 * Строка состояния синхронизации под карточкой «Календари».
 *
 * Формулировки повторяют Android, кроме времени последней синхронизации: на desktop
 * результат живёт до перезапуска окна, а после него видно только время из settings.json.
 */
internal fun syncStatusText(
    result: SyncResult?,
    lastSyncTime: Long,
    syncing: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (syncing) return "Синхронизация…"
    if (result == null) {
        return if (lastSyncTime > 0L) {
            "Последняя синхронизация: " +
                lastSyncFormat.format(Instant.ofEpochMilli(lastSyncTime).atZone(zone))
        } else {
            "Синхронизация ещё не выполнялась"
        }
    }
    val message = result.message
    return when {
        message == "not_connected" -> "Календарь не подключён"
        message != null && message.startsWith("partial:") ->
            "Синхронизировано частично: ${message.removePrefix("partial:")}"

        !result.success -> "Ошибка синхронизации: ${message ?: "неизвестная ошибка"}"
        else -> "Готово: отправлено ${result.pushed}, получено ${result.pulled}, " +
            "конфликтов ${result.conflicts}"
    }
}
