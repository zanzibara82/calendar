package com.example.mycalendar.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.example.mycalendar.R
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.work.ReminderScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Кнопка «Отложить на 10 мин» в уведомлении-напоминании.
 *
 * Делает три вещи:
 *  1. снимает уведомление (иначе оно висит до тапа по телу);
 *  2. гасит зацикленный сигнал и закрывает [ReminderActivity], если экран
 *     напоминания сейчас открыт — иначе звук продолжит играть после откладывания;
 *  3. ставит новый точный будильник через [ReminderScheduler.SNOOZE_MINUTES].
 *
 * Заявка на отложенное напоминание живёт в AlarmManager, поэтому переживает
 * смерть процесса и перезагрузку (перезагрузку — см. BootReceiver: он
 * перепланирует напоминания events, но разовый snooze при этом теряется —
 * приемлемо для 10-минутной отсрочки).
 */
@AndroidEntryPoint
class SnoozeReceiver : BroadcastReceiver() {

    @Inject lateinit var eventRepository: EventRepository
    @Inject lateinit var reminderScheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SNOOZE) {
            Log.w(TAG, "onReceive: unexpected action=${intent.action}, ignoring")
            return
        }
        val eventId = intent.getStringExtra(NotificationHelper.EXTRA_EVENT_ID)
        if (eventId == null) {
            Log.e(TAG, "onReceive: eventId is null, aborting")
            return
        }
        Log.d(TAG, "onReceive: snooze eventId=$eventId")

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                // 1. Убираем уведомление независимо от того, открывал ли его пользователь
                NotificationManagerCompat.from(context).cancel(notificationIdFor(eventId))

                // 2. Останавливаем зацикленный звук аларм-экрана (Android 9 и ниже
                //    ReminderActivity запускается напрямую из AlarmReceiver).
                ReminderActivity.finishIfRunning()

                // 3. Перепланируем напоминание
                val event = eventRepository.getEvent(eventId)
                if (event == null) {
                    Log.w(TAG, "  event not found in DB, nothing to snooze")
                } else {
                    reminderScheduler.scheduleSnooze(event, ReminderScheduler.SNOOZE_MINUTES)
                    Log.d(TAG, "  snoozed for ${ReminderScheduler.SNOOZE_MINUTES} min")
                    showSnoozeToast(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "  ERROR in onReceive", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showSnoozeToast(context: Context) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                context.getString(R.string.notification_snoozed, ReminderScheduler.SNOOZE_MINUTES),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    companion object {
        private const val TAG = "SnoozeReceiver"
        const val ACTION_SNOOZE = "com.example.mycalendar.ACTION_SNOOZE"

        /** id уведомления события — совпадает с тем, что использует NotificationHelper. */
        fun notificationIdFor(eventId: String): Int = eventId.hashCode()

        fun createIntent(context: Context, eventId: String): Intent =
            Intent(context, SnoozeReceiver::class.java).apply {
                action = ACTION_SNOOZE
                putExtra(NotificationHelper.EXTRA_EVENT_ID, eventId)
            }
    }
}
