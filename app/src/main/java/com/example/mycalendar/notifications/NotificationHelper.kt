package com.example.mycalendar.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.mycalendar.MainActivity
import com.example.mycalendar.R
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.work.ReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Показ напоминаний о событиях.
 *
 * Ключевая логика:
 *  - канал со звуком СИСТЕМНОГО БУДИЛЬНИКА (fallback: рингтон/уведомление),
 *    вибрацией и importance HIGH — звучит даже в тихом режиме медиа;
 *  - full-screen intent: уведомление всплывает окошком в центре экрана
 *    ([ReminderActivity]) и включается поверх локскрина даже при погашенном экране;
 *  - идём через alarm-атрибутику (USAGE_ALARM), как будильник.
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "NotificationHelper"
        // v2: новая версия канала, чтобы звук/атрибутика применились у обновившихся установок
        const val CHANNEL_REMINDERS = "channel_event_reminders_v2"
        const val EXTRA_EVENT_ID = "extra_event_id"

        /** Размер отсрочки кнопки «Отложить» — один источник правды с ReminderScheduler. */
        const val SNOOZE_MINUTES = ReminderScheduler.SNOOZE_MINUTES

        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }

    fun createChannels() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Системный звук будильника; при его отсутствии — рингтон, затем звук уведомлений
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alarmAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val remindersChannel = NotificationChannel(
            CHANNEL_REMINDERS,
            context.getString(R.string.notification_channel_reminders),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_reminders_desc)
            setSound(alarmSound, alarmAttributes)
            enableVibration(true)
            setBypassDnd(false)
        }
        manager.createNotificationChannel(remindersChannel)
    }

    /**
     * Текст-подсказка для напоминания: «Начало в 09:30 (через 5 мин)».
     * Вынесен отдельно, чтобы AlarmReceiver мог передать его в ReminderActivity.
     *
     * [reminderMinutes] <= 0 (отложенное напоминание) — вместо «через 0 мин»
     * печатается «Событие начинается».
     */
    fun buildReminderLabel(occurrenceStart: Long, reminderMinutes: Int): String {
        if (reminderMinutes <= 0) return context.getString(R.string.notification_reminder_now)
        val zone = ZoneId.systemDefault()
        val startLocal = Instant.ofEpochMilli(occurrenceStart).atZone(zone)
        return context.getString(
            R.string.notification_reminder_text,
            startLocal.format(timeFormatter),
            humanizeReminder(reminderMinutes),
        )
    }

    /**
     * Уведомление «Событие через N минут»:
     *  - full-screen intent -> [ReminderActivity] (всплывает в центре, включает экран);
     *  - тап по самому уведомлению -> детали события в MainActivity.
     */
    fun showEventReminder(
        event: Event,
        reminderMinutes: Int,
        occurrenceStart: Long,
        occurrenceEnd: Long,
    ) {
        Log.d(TAG, "showEventReminder: eventId=${event.id} title='${event.title}' " +
            "reminderMinutes=$reminderMinutes canPost=${canPostNotifications()}")

        if (!canPostNotifications()) {
            Log.w(TAG, "  cannot post notifications (permission denied), aborting")
            return
        }

        // Убеждаемся, что канал создан
        createChannels()
        Log.d(TAG, "  channels created, channel=$CHANNEL_REMINDERS")

        val startTime = occurrenceStart.takeIf { it > 0 } ?: event.startTime
        val label = buildReminderLabel(startTime, reminderMinutes)

        // Полноэкранный запуск аларм-экрана
        val fullScreenIntent = Intent(context, ReminderActivity::class.java).apply {
            putExtra(EXTRA_EVENT_ID, event.id)
            putExtra(ReminderActivity.EXTRA_TITLE, event.title)
            putExtra(ReminderActivity.EXTRA_TIME_TEXT, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            event.id.hashCode(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Тап по уведомлению -> детали события
        val contentIntent = PendingIntent.getActivity(
            context,
            event.id.hashCode(),
            MainActivity.createIntent(context, event.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Кнопка «Открыть событие»: тот же переход, что по тапу по телу,
        // но явно видимый — раньше попасть в событие можно было только так.
        val openActionIntent = PendingIntent.getActivity(
            context,
            event.id.hashCode() + 1,
            MainActivity.createIntent(context, event.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Кнопка «Отложить на 10 мин»
        val snoozeActionIntent = PendingIntent.getBroadcast(
            context,
            event.id.hashCode() + 2,
            SnoozeReceiver.createIntent(context, event.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(event.title)
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(
                R.drawable.ic_open_event_24,
                context.getString(R.string.notification_open_event),
                openActionIntent,
            )
            .addAction(
                R.drawable.ic_snooze_24,
                context.getString(R.string.notification_snooze, SNOOZE_MINUTES),
                snoozeActionIntent,
            )
            .build()

        Log.d(TAG, "  calling notify(id=${event.id.hashCode()}, notification)")
        NotificationManagerCompat.from(context).notify(event.id.hashCode(), notification)
        Log.d(TAG, "  notify() completed")
    }

    /** Проверка POST_NOTIFICATIONS (Android 13+); на более старых системах всегда true. */
    private fun canPostNotifications(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Может ли приложение показывать full-screen уведомления (для экрана разрешений). */
    fun canUseFullScreenIntent(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.canUseFullScreenIntent()
        } else {
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        }

    private fun humanizeReminder(minutes: Int): String = when {
        minutes >= 1440 -> context.getString(R.string.days_short, minutes / 1440)
        minutes >= 60 -> context.getString(R.string.hours_short, minutes / 60)
        else -> context.getString(R.string.minutes_short, minutes)
    }
}
