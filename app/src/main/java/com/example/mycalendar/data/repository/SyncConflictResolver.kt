package com.example.mycalendar.data.repository

import com.example.mycalendar.data.remote.RemoteEvent
import com.example.mycalendar.domain.model.Event
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Разрешение конфликтов синхронизации по стратегии last-write-wins (LWW):
 * побеждает версия с более поздним updatedAt; при равенстве приоритет у локальной
 * версии (чтобы не «дёргать» облако без нужды).
 *
 * Вынесен в отдельный класс без Android-зависимостей — легко покрыть unit-тестами.
 * Ручной merge (показ диалога выбора версии) — возможное расширение через
 * возвращение Resolution.NEEDS_MERGE.
 */
@Singleton
class SyncConflictResolver @Inject constructor() {

    enum class Resolution { KEEP_LOCAL, TAKE_REMOTE }

    fun resolve(local: Event, remote: RemoteEvent): Resolution = when {
        remote.updatedAt > local.updatedAt -> Resolution.TAKE_REMOTE
        else -> Resolution.KEEP_LOCAL
    }
}
