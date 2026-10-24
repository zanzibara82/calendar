package com.example.mycalendar.data.remote

import com.example.mycalendar.data.remote.yandex.YandexCalendarDataSource
import com.example.mycalendar.data.system.SystemCalendarDataSource
import com.example.mycalendar.domain.model.CalendarProvider
import javax.inject.Inject
import javax.inject.Singleton

/** Google — через системный календарь Android, Яндекс — через CalDAV. */
@Singleton
class CalendarDataSourceProvider @Inject constructor(
    private val systemCalendarDataSource: SystemCalendarDataSource,
    private val yandexCalendarDataSource: YandexCalendarDataSource,
) : RemoteDataSourceProvider {

    override fun forProvider(provider: CalendarProvider): RemoteCalendarDataSource? = when (provider) {
        CalendarProvider.GOOGLE -> systemCalendarDataSource
        CalendarProvider.YANDEX -> yandexCalendarDataSource
        CalendarProvider.LOCAL -> null
    }
}
