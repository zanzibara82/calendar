package com.example.mycalendar.desktop.data

import com.example.mycalendar.data.remote.RemoteCalendarDataSource
import com.example.mycalendar.data.remote.RemoteDataSourceProvider
import com.example.mycalendar.data.remote.yandex.YandexCalendarDataSource
import com.example.mycalendar.domain.model.CalendarProvider

/**
 * Источники desktop-клиента: у Яндекса тот же CalDAV, что и на Android.
 *
 * Google остаётся без источника: на Android он подключается через системный календарь
 * (CalendarContract), которого на Windows нет. Собственный Google-клиент потребует
 * отдельного OAuth и REST-API — это отдельная задача, а не молчаливая заглушка.
 */
class DesktopRemoteDataSourceProvider(
    private val yandex: YandexCalendarDataSource,
) : RemoteDataSourceProvider {

    override fun forProvider(provider: CalendarProvider): RemoteCalendarDataSource? = when (provider) {
        CalendarProvider.YANDEX -> yandex
        CalendarProvider.GOOGLE, CalendarProvider.LOCAL -> null
    }
}
