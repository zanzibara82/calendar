package com.example.mycalendar.desktop.reminder

import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.reminder.ReminderScheduler

/**
 * Напоминания Windows пока не доставляются: системные уведомления появятся отдельным шагом.
 * Заглушка нужна, чтобы общий движок синхронизации работал без Android AlarmManager
 * и не требовал условных веток на каждый вызов планировщика.
 */
class DesktopReminderScheduler : ReminderScheduler {

    override fun schedule(event: Event) = Unit

    override fun cancel(event: Event) = Unit
}
