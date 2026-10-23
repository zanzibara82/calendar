package com.example.mycalendar

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.notifications.NotificationHelper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Проверка кнопок уведомления-напоминания: «Открыть событие» и «Отложить на 10 мин».
 *
 * Исторически NotificationHelper не добавлял addAction, поэтому попасть в событие
 * можно было только тапом по телу уведомления. Тест фиксирует, что:
 *  - обе кнопки присутствуют в построенном уведомлении;
 *  - нажатие «Отложить» снимает уведомление (иначе оно висит до тапа по телу).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NotificationActionsTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var eventRepository: EventRepository

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    private lateinit var event: Event

    @Before
    fun setUp() {
        hiltRule.inject()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // На Android 8+ канал обязателен, иначе уведомление просто не покажется
        notificationHelper.createChannels()

        val now = System.currentTimeMillis()
        event = Event(
            id = "notif-actions-${System.nanoTime()}",
            title = "Событие с кнопками ${System.currentTimeMillis() % 100_000}",
            description = "Проверка addAction в уведомлении",
            startTime = now + 60 * 60 * 1000,
            endTime = now + 2 * 60 * 60 * 1000,
            reminderMinutes = listOf(10),
            calendarProvider = CalendarProvider.LOCAL,
        )
        // Кладём событие в БД: иначе SnoozeReceiver его не найдёт и просто
        // не перенесёт напоминание — тест при этом зелёный, но ни о чём.
        runBlocking { eventRepository.saveFromUser(event) }
    }

    @After
    fun tearDown() {
        runCatching { manager.cancel(event.id.hashCode()) }
        runCatching { runBlocking { eventRepository.hardDelete(event.id) } }
    }

    @Test
    fun reminder_hasOpenAndSnoozeActions() {
        notificationHelper.showEventReminder(
            event = event,
            reminderMinutes = 10,
            occurrenceStart = event.startTime,
            occurrenceEnd = event.endTime,
        )

        val notification = findReminderNotification()
        assertNotNull("Уведомление-напоминание не найдено в активных", notification)
        val actions = notification!!.actions
        assertNotNull("У уведомления нет кнопок (addAction не вызван)", actions)
        assertEquals("Ожидались ровно две кнопки", 2, actions!!.size)

        val titles = actions.map { it.title.toString() }
        assertTrue(
            "Нет кнопки «Открыть событие»: $titles",
            titles.any { it == context.getString(R.string.notification_open_event) },
        )
        assertTrue(
            "Нет кнопки «Отложить на 10 мин»: $titles",
            titles.any {
                it == context.getString(R.string.notification_snooze, NotificationHelper.SNOOZE_MINUTES)
            },
        )
    }

    @Test
    fun snoozeAction_cancelsNotification() {
        notificationHelper.showEventReminder(
            event = event,
            reminderMinutes = 10,
            occurrenceStart = event.startTime,
            occurrenceEnd = event.endTime,
        )
        assertNotNull("Уведомление не было показано", findReminderNotification())

        val snoozeAction = findReminderNotification()!!.actions!!
            .first { it.title.toString() == context.getString(R.string.notification_snooze, NotificationHelper.SNOOZE_MINUTES) }

        // Нажатие «Отложить» = отправка PendingIntent'а кнопки
        runCatching { snoozeAction.actionIntent.send() }
        // SnoozeReceiver работает асинхронно (goAsync + корутина) — ждём завершения
        waitUntil(5_000) { findReminderNotification() == null }

        assertNull(
            "После «Отложить» уведомление должно быть снято",
            findReminderNotification(),
        )
    }

    // ---------- helpers ----------

    private fun findReminderNotification(): Notification? =
        manager.activeNotifications
            .firstOrNull { it.id == event.id.hashCode() }
            ?.notification

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(100)
        }
    }
}
