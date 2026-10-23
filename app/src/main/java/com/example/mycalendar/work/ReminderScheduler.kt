package com.example.mycalendar.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.util.RepeatRuleHelper
import com.example.mycalendar.notifications.AlarmReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Планирование напоминаний через AlarmManager (точные будильники).
 *
 * Ключевая логика:
 *  - точное время: setExactAndAllowWhileIdle — срабатывает даже в Doze;
 *    если разрешение SCHEDULE_EXACT_ALARM не выдано (Android 14+ по умолчанию
 *    выключено) — деградация до setWindow с узким окном;
 *  - для повторяющихся событий планируется только БЛИЖАЙШЕЕ вхождение;
 *    AlarmReceiver после срабатывания планирует следующее (цепочка);
 *  - будильник идентифицируется data-URI "mycalendar://reminder/{eventId}/{minutes}",
 *    поэтому cancel по списку минут события снимает именно его будильники.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /** (Пере)планирует все напоминания события для ближайшего вхождения в будущем. */
    fun schedule(event: Event) {
        Log.d(TAG, "schedule() called for event id=${event.id} title='${event.title}' " +
            "startTime=${sdf.format(Date(event.startTime))} repeatRule=${event.repeatRule} " +
            "reminders=${event.reminderMinutes}")

        cancel(event)
        val now = System.currentTimeMillis()
        Log.d(TAG, "  now=${sdf.format(Date(now))}")

        val occurrenceStart = RepeatRuleHelper.nextOccurrenceAfter(event.startTime, event.repeatRule, now - 1)
            ?: event.startTime.takeIf { it > now }
            ?: run {
                Log.d(TAG, "  SKIP: event is in the past and not repeating")
                return // событие в прошлом и не повторяется
            }
        val occurrenceEnd = occurrenceStart + (event.endTime - event.startTime)
        Log.d(TAG, "  occurrenceStart=${sdf.format(Date(occurrenceStart))} " +
            "occurrenceEnd=${sdf.format(Date(occurrenceEnd))}")

        if (event.reminderMinutes.isEmpty()) {
            Log.d(TAG, "  SKIP: no reminders set for this event")
            return
        }

        event.reminderMinutes.distinct().forEach { minutes ->
            val triggerAt = occurrenceStart - minutes * 60_000L
            Log.d(TAG, "  reminder $minutes min: triggerAt=${sdf.format(Date(triggerAt))} " +
                "(occurrenceStart - ${minutes}min)")
            when {
                // Обычный случай: напоминание в будущем
                triggerAt > now -> {
                    Log.d(TAG, "    -> scheduling at ${sdf.format(Date(triggerAt))} (future)")
                    scheduleOneTime(event, minutes, triggerAt, occurrenceStart, occurrenceEnd)
                }

                // Событие ещё НЕ началось, а время напоминания уже прошло
                // (событие создали незадолго до начала): срабатываем немедленно,
                // иначе пользователь вообще не увидит напоминание
                occurrenceStart > now -> {
                    Log.d(TAG, "    -> scheduling NOW+1.5s (reminder time passed but event not started)")
                    scheduleOneTime(event, minutes, now + 1_500L, occurrenceStart, occurrenceEnd)
                }

                // Событие уже началось/прошло — напоминание не нужно
                else -> {
                    Log.d(TAG, "    -> SKIP (event already started/passed)")
                }
            }
        }
    }

    /** Планирование следующего вхождения повторяющегося события (вызывается AlarmReceiver'ом). */
    fun scheduleNextOccurrence(event: Event, previousStart: Long) {
        val rule = event.repeatRule ?: return
        val now = System.currentTimeMillis()
        val nextStart = RepeatRuleHelper.nextOccurrenceAfter(event.startTime, rule, previousStart) ?: return
        val nextEnd = nextStart + (event.endTime - event.startTime)
        event.reminderMinutes.distinct().forEach { minutes ->
            val triggerAt = nextStart - minutes * 60_000L
            if (triggerAt <= now) return@forEach
            scheduleOneTime(event, minutes, triggerAt, nextStart, nextEnd)
        }
    }

    /**
     * Установка одного будильника. Открыта для instrumented-тестов
     * (детерминированная проверка цепочки "будильник -> уведомление").
     */
    fun scheduleOneTime(
        event: Event,
        minutes: Int,
        triggerAt: Long,
        occurrenceStart: Long,
        occurrenceEnd: Long,
    ) {
        val pendingIntent = buildPendingIntent(event.id, minutes, occurrenceStart, occurrenceEnd)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        Log.d(TAG, "scheduleOneTime: eventId=${event.id} minutes=$minutes " +
            "triggerAt=${sdf.format(Date(triggerAt))} canExact=$canExact " +
            "sdk=${Build.VERSION.SDK_INT}")
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            // Fallback: окно ±15 секунд вместо точного срабатывания
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, FALLBACK_WINDOW_MILLIS, pendingIntent)
        }
    }

    /**
     * Отложить напоминание: сработает через [delayMinutes] минут от текущего момента.
     *
     * Используется кнопкой «Отложить на 10 мин» в уведомлении
     * (см. [com.example.mycalendar.notifications.SnoozeReceiver]).
     *
     * minutes = 0 означает «событие начинается» — именно такую подпись
     * формирует NotificationHelper.buildReminderLabel для отложенного срабатывания
     * (вместо бессмысленного «через 0 мин»).
     */
    fun scheduleSnooze(event: Event, delayMinutes: Int = SNOOZE_MINUTES) {
        val triggerAt = System.currentTimeMillis() + delayMinutes * 60_000L
        Log.d(TAG, "scheduleSnooze: eventId=${event.id} in ${delayMinutes}min " +
            "(at ${sdf.format(Date(triggerAt))})")
        scheduleOneTime(
            event = event,
            minutes = SNOOZE_MINUTES_MARKER,
            triggerAt = triggerAt,
            occurrenceStart = event.startTime,
            occurrenceEnd = event.endTime,
        )
    }

    /**
     * Отмена всех напоминаний события (при удалении или редактировании).
     *
     * Кроме обычных минут дополнительно снимается маркер отложенного
     * напоминания ([SNOOZE_MINUTES_MARKER]) — иначе «Отложить на 10 мин»
     * выстрелило бы уже по удалённому событию.
     */
    fun cancel(event: Event) {
        (event.reminderMinutes.distinct() + SNOOZE_MINUTES_MARKER).forEach { minutes ->
            alarmManager.cancel(buildPendingIntent(event.id, minutes, 0L, 0L))
        }
    }

    /**
     * PendingIntent будильника. Совпадение PendingIntent'ов определяется
     * компонентом + action + data (extras НЕ участвуют), поэтому уникальность
     * пары (событие, напоминание) задаётся через data-URI.
     */
    private fun buildPendingIntent(
        eventId: String,
        minutes: Int,
        occurrenceStart: Long,
        occurrenceEnd: Long,
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE_REMINDER
            data = Uri.parse("mycalendar://reminder/$eventId/$minutes")
            putExtra(AlarmReceiver.EXTRA_EVENT_ID, eventId)
            putExtra(AlarmReceiver.EXTRA_REMINDER_MINUTES, minutes)
            putExtra(AlarmReceiver.EXTRA_OCCURRENCE_START, occurrenceStart)
            putExtra(AlarmReceiver.EXTRA_OCCURRENCE_END, occurrenceEnd)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "ReminderScheduler"
        private const val REQUEST_CODE = 42
        const val FALLBACK_WINDOW_MILLIS = 15_000L

        /** На сколько минут откладывает кнопка «Отложить» в уведомлении. */
        const val SNOOZE_MINUTES = 10

        /**
         * Маркер отложенного напоминания в data-URI будильника.
         * -1 исключает совпадение с реальным напоминанием события.
         */
        const val SNOOZE_MINUTES_MARKER = -1

        fun workTagFor(eventId: String) = "reminder_event_$eventId"
    }
}
