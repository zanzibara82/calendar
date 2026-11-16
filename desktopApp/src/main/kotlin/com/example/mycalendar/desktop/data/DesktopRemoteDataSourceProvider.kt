package com.example.mycalendar.desktop.data

import com.example.mycalendar.data.remote.RemoteCalendarDataSource
import com.example.mycalendar.data.remote.RemoteDataSourceProvider
import com.example.mycalendar.domain.model.CalendarProvider

/** Подключённые к окну источники: Google и Яндекс независимы, LOCAL — только локально. */
class DesktopRemoteDataSourceProvider(
    private val google: RemoteCalendarDataSource,
    private val yandex: RemoteCalendarDataSource,
) : RemoteDataSourceProvider {

    override fun forProvider(provider: CalendarProvider): RemoteCalendarDataSource? =
        when (provider) {
            CalendarProvider.GOOGLE -> google
            CalendarProvider.YANDEX -> yandex
            CalendarProvider.LOCAL -> null
        }
}
