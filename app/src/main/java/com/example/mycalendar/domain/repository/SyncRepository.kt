package com.example.mycalendar.domain.repository

import com.example.mycalendar.domain.model.CalendarProvider

/**
 * Результат одной итерации двусторонней синхронизации.
 */
data class SyncResult(
    val success: Boolean,
    /** Количество локальных изменений, отправленных в облако. */
    val pushed: Int = 0,
    /** Количество событий, подтянутых из облака. */
    val pulled: Int = 0,
    /** Количество событий, поставленных в очередь открытых конфликтов. */
    val conflicts: Int = 0,
    val message: String? = null,
    /** Провайдер, к которому относится результат (null — общий/гостевой). */
    val provider: CalendarProvider? = null,
) {
    companion object {
        fun guest() = SyncResult(success = false, message = "guest")

        fun failure(provider: CalendarProvider, message: String) =
            SyncResult(success = false, message = message, provider = provider)

        /** Ни один календарь не подключён — синхронизировать нечего. */
        fun nothingConnected() =
            SyncResult(success = false, message = "not_connected")
    }
}

/**
 * Двусторонняя синхронизация локальной БД с календарями провайдеров.
 *
 * Провайдеры независимы: каждый синхронизируется отдельно
 * ([syncProvider]) или все разом ([syncNow]).
 */
interface SyncRepository {
    /** Синхронизация всех подключённых календарей; возвращает сводный результат. */
    suspend fun syncNow(): SyncResult

    /** Синхронизация одного календаря. */
    suspend fun syncProvider(provider: CalendarProvider): SyncResult
}
