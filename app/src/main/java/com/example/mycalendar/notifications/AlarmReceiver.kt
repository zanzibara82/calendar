package com.example.mycalendar.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.work.ReminderScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Срабатывание напоминания по точному будильнику AlarmManager.
 *
 * Делает ДВЕ вещи:
 *  1. Показывает уведомление (со звуком канала + full-screen intent для
 *     выключенного экрана: всплывает поверх локскрина);
 *  2. НАПРЯМУЮ запускает [ReminderActivity] — окно с текстом напоминания и
 *     зацикленным звуком всплывает в центре экрана ТАКЖЕ при активном экране,
 *     когда full-screen intent подавляется системой.
 */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var eventRepository: EventRepository
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var reminderScheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action} intent=$intent")
        if (intent.action != ACTION_FIRE_REMINDER) {
            Log.w(TAG, "  unexpected action, ignoring")
            return
        }
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
                val minutes = intent.getIntExtra(EXTRA_REMINDER_MINUTES, 0)
                val occurrenceStart = intent.getLongExtra(EXTRA_OCCURRENCE_START, 0L)
                val occurrenceEnd = intent.getLongExtra(EXTRA_OCCURRENCE_END, 0L)
                Log.d(TAG, "  eventId=$eventId minutes=$minutes " +
                    "occurrenceStart=$occurrenceStart occurrenceEnd=$occurrenceEnd")

                if (eventId == null) {
                    Log.e(TAG, "  eventId is null, aborting")
                    return@launch
                }

                val event = eventRepository.getEvent(eventId)
                if (event == null) {
                    Log.e(TAG, "  event not found in DB, aborting")
                    return@launch
                }
                Log.d(TAG, "  event found: title='${event.title}' reminders=${event.reminderMinutes}")

                val startTime = occurrenceStart.takeIf { it > 0 } ?: event.startTime
                val endTime = occurrenceEnd.takeIf { it > 0 } ?: event.endTime
                val label = notificationHelper.buildReminderLabel(startTime, minutes)

                // 1. Уведомление (звук канала + full-screen intent для погашенного экрана)
                notificationHelper.showEventReminder(
                    event = event,
                    reminderMinutes = minutes,
                    occurrenceStart = startTime,
                    occurrenceEnd = endTime,
                )
                Log.d(TAG, "  notification shown successfully")

                // 2. Прямой запуск ReminderActivity — окно всплывает и при активном экране.
                //    На Android 9 (API 28) fullScreenIntent подавляется, если экран активен,
                //    поэтому запускаем activity вручную. WAKE_LOCK гарантирует, что CPU не уснёт.
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "mycalendar:reminder_fire",
                ).apply { acquire(30_000) }

                try {
                    val alarmIntent = Intent(context, ReminderActivity::class.java).apply {
                        putExtra(NotificationHelper.EXTRA_EVENT_ID, event.id)
                        putExtra(ReminderActivity.EXTRA_TITLE, event.title)
                        putExtra(ReminderActivity.EXTRA_TIME_TEXT, label)
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                                or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        )
                    }
                    Log.d(TAG, "  starting ReminderActivity directly")
                    context.startActivity(alarmIntent)
                } finally {
                    wakeLock.release()
                }

                // 3. Цепочка повторений: планируем следующее вхождение события
                if (event.repeatRule != null) {
                    Log.d(TAG, "  scheduling next occurrence for repeating event")
                    reminderScheduler.scheduleNextOccurrence(event, occurrenceStart)
                }
            } catch (e: Exception) {
                Log.e(TAG, "  ERROR in onReceive", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_FIRE_REMINDER = "com.example.mycalendar.ACTION_FIRE_REMINDER"
        const val EXTRA_EVENT_ID = "extra_event_id"
        const val EXTRA_REMINDER_MINUTES = "extra_reminder_minutes"
        const val EXTRA_OCCURRENCE_START = "extra_occurrence_start"
        const val EXTRA_OCCURRENCE_END = "extra_occurrence_end"
    }
}
