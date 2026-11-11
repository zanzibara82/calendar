package com.example.mycalendar

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.ReminderSound
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.notifications.NotificationHelper
import com.example.mycalendar.notifications.ReminderActivity
import com.example.mycalendar.work.ReminderScheduler
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Тап «Отложить» в оверлее рассылает SnoozeReceiver.ACTION_SNOO с верным EXTRA_EVENT_ID
 * и закрывает активность. Доказательство верного eventId: SnoozeReceiver снимает уведомление
 * с id == eventId.hashCode() — заранее публикуем такое уведомление и проверяем, что оно исчезло.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OverlaySnoozeTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject lateinit var eventRepository: EventRepository
    @Inject lateinit var reminderScheduler: ReminderScheduler
    @Inject lateinit var notificationHelper: NotificationHelper

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var event: Event

    @Before
    fun setUp() {
        hiltRule.inject()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching {
            ui.executeShellCommand("input keyevent KEYCODE_WAKEUP")
            ui.executeShellCommand("svc power stayon true")
            ui.executeShellCommand("wm dismiss-keyguard")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                ui.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            }
        }
        notificationHelper.createChannels()
        event = Event(
            id = "overlay-snooze-${System.nanoTime()}",
            title = "Отложить из оверлея",
            startTime = System.currentTimeMillis() + 3_600_000L,
            endTime = System.currentTimeMillis() + 7_200_000L,
            reminderMinutes = listOf(10),
        )
        runBlocking { eventRepository.saveFromUser(event) }
        // Простое уведомление без full-screen intent, чтобы не было второго запуска оверлея.
        val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(event.title)
            .build()
        NotificationManagerCompat.from(context).notify(event.id.hashCode(), notification)
    }

    @After
    fun tearDown() {
        runCatching { reminderScheduler.cancel(event) }
        runCatching { manager.cancel(event.id.hashCode()) }
        runCatching { runBlocking { eventRepository.hardDelete(event.id) } }
    }

    @Test
    fun snoozeButton_broadcastsAndClosesOverlay() {
        val intent = ReminderActivity.createIntent(context, event, ReminderSound.Builtin, "09:00")
        ActivityScenario.launch<ReminderActivity>(intent).use { scenario ->
            composeRule.waitForIdle()
            composeRule.onNodeWithText(context.getString(R.string.overlay_snooze)).performClick()
            // SnoozeReceiver асинхронен (goAsync + корутина): ждём снятия уведомления и DESTROYED.
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val cancelled = manager.activeNotifications.none { it.id == event.id.hashCode() }
                if (cancelled && scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED) break
                Thread.sleep(100)
            }
            assertTrue(
                "После «Отложить» уведомление с id eventId.hashCode() должно быть снято",
                manager.activeNotifications.none { it.id == event.id.hashCode() },
            )
            assertTrue(
                "Оверлей должен закрыться",
                scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED,
            )
        }
    }
}
