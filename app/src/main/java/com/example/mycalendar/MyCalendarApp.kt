package com.example.mycalendar

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.mycalendar.notifications.NotificationHelper
import com.example.mycalendar.work.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application-класс приложения:
 *  - Hilt-граф зависимостей;
 *  - конфигурация WorkManager с HiltWorkerFactory (инижект зависимостей в воркеры);
 *  - каналы уведомлений;
 *  - периодическая фоновая синхронизация.
 */
@HiltAndroidApp
class MyCalendarApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notificationHelper: NotificationHelper

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        notificationHelper.createChannels()
        SyncWorker.schedulePeriodic(this)
    }
}
