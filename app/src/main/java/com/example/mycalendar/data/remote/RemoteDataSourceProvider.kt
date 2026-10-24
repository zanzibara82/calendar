package com.example.mycalendar.data.remote

import com.example.mycalendar.domain.model.CalendarProvider

/**
 * Удалённый источник событий провайдера. Отдельный интерфейс потому, что
 * сквозной тест синхронизации должен подменять облако фиксом, не трогая Room.
 * `fun interface` — чтобы подмена в тесте писалась лямбдой.
 */
fun interface RemoteDataSourceProvider {
    /** null — провайдер не обслуживается (LOCAL либо неподключённый). */
    fun forProvider(provider: CalendarProvider): RemoteCalendarDataSource?
}
