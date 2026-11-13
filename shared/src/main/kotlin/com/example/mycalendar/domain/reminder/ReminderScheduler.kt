package com.example.mycalendar.domain.reminder

import com.example.mycalendar.domain.model.Event

/**
 * Напоминания события глазами домена: движок синхронизации и сценарии правки
 * говорят «закажи» и «сними», а способ доставки выбирает клиент —
 * AlarmManager на Android, системные уведомления на Windows.
 *
 * [cancel] обязан снимать и отложенное напоминание, а не только минуты из
 * [Event.reminderMinutes]: иначе «Отложить» выстрелит по удалённому событию.
 */
interface ReminderScheduler {
    /** (Пере)планирует напоминания события. */
    fun schedule(event: Event)

    /** Снимает все заказы события, включая отложенные. */
    fun cancel(event: Event)
}
