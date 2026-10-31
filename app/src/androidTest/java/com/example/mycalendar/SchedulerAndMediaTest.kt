package com.example.mycalendar

import android.app.Notification
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.mycalendar.data.audio.VoiceRecorder
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.notifications.NotificationHelper
import com.example.mycalendar.notifications.ReminderActivity
import com.example.mycalendar.work.ReminderScheduler
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Поведенческие тесты, чувствительные к версии Android:
 *  - полная цепочка напоминания: точный будильник AlarmManager -> AlarmReceiver ->
 *    уведомление с full-screen intent (проверяется фактическое всплытие);
 *  - запись голоса MediaRecorder (API 28: deprecated-конструктор, API 31+: context-конструктор).
 */
@LargeTest
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SchedulerAndMediaTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var eventRepository: EventRepository
    @Inject lateinit var reminderScheduler: ReminderScheduler
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var voiceRecorder: VoiceRecorder

    private val createdIds = mutableListOf<String>()

    private companion object {
        private const val TAG = "SchedulerAndMediaTest"
    }

    @Before
    fun setUp() {
        hiltRule.inject()
        // POST_NOTIFICATIONS на Android 13+: выдаём тихо, без системного диалога
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    "pm grant ${InstrumentationRegistry.getInstrumentation().targetContext.packageName}" +
                        " android.permission.POST_NOTIFICATIONS"
                )
            }
        }
        // В тестовом процессе App.onCreate не выполняется — канал создаём сами (идемпотентно)
        notificationHelper.createChannels()
    }

    @After
    fun tearDown() {
        runBlocking {
            createdIds.forEach { id ->
                val event = eventRepository.getEvent(id)
                if (event != null) reminderScheduler.cancel(event)
                eventRepository.hardDelete(id)
            }
        }
        voiceRecorder.discard()
    }

    private fun api() = "on API " + Build.VERSION.SDK_INT

    /**
     * Полная цепочка: ставим будильник через 1.5 секунды и ждём фактического
     * уведомления. Проверяет AlarmManager-планирование, работу AlarmReceiver
     * и постинг уведомления (в т.ч. POST_NOTIFICATIONS-поведение на API 33+).
     */
    @Test
    fun alarmChain_firesAndPostsNotification() {
        val now = System.currentTimeMillis()
        val event = Event(
            id = UUID.randomUUID().toString(),
            title = "Alarm test " + Build.VERSION.SDK_INT,
            startTime = now + TimeUnit.HOURS.toMillis(2),
            endTime = now + TimeUnit.HOURS.toMillis(3),
            reminderMinutes = listOf(5),
            updatedAt = now,
        )
        createdIds.add(event.id)
        runBlocking { eventRepository.saveFromUser(event) }

        // Триггер в ближайшие ~1.5 сек: при отказе в SCHEDULE_EXACT_ALARM
        // планировщик деградирует до setWindow(15 сек), поэтому порог ожидания — 30 сек
        reminderScheduler.scheduleOneTime(
            event = event,
            minutes = 5,
            triggerAt = now + 1_500L,
            occurrenceStart = event.startTime,
            occurrenceEnd = event.endTime,
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deadline = System.currentTimeMillis() + 30_000
        var posted = false
        while (System.currentTimeMillis() < deadline) {
            posted = NotificationManagerCompat.from(context).activeNotifications.any {
                it.notification.extras.getString(Notification.EXTRA_TITLE) == event.title
            }
            if (posted) break
            Thread.sleep(500)
        }
        assertTrue("Уведомление не появилось ${api()}", posted)

        // AlarmReceiver сразу после уведомления поднимает ReminderActivity.
        // Дожидаемся её ДО конца теста: MonitoringInstrumentation закрывает только
        // те активности, которые успел увидеть. Иначе активность создаётся уже после
        // уничтожения Hilt-компонента и падает в onCreate — так упал
        // SyncConflictEndToEndTest 2026-10-31 (всплытие пришло через 1.2 с).
        // На части версий Android фоновый старт активности подавлен: тогда всплытия
        // нет вовсе и «позднего» старта тоже не будет — это не провал теста.
        if (!awaitReminderActivityResumed()) {
            Log.i(TAG, "ReminderActivity не появилась за 15 с: платформа подавила фон-старт")
        }
    }

    /** Ждёт, пока ReminderActivity дойдёт до RESUMED (видна системе тестов). */
    private fun awaitReminderActivityResumed(timeoutMs: Long = 15_000): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var found = false
            instrumentation.runOnMainSync {
                found = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .any { it is ReminderActivity }
            }
            if (found) return true
            Thread.sleep(250)
        }
        return false
    }

    @Test
    fun voiceRecorder_recordsM4aFile() {
        val startResult = voiceRecorder.start()
        if (startResult.isFailure &&
            startResult.exceptionOrNull() is java.lang.RuntimeException &&
            startResult.exceptionOrNull()?.message?.contains("setAudioSource") == true
        ) {
            // Headless-эмулятор без аудио-бэкенда хоста: микрофона нет физически.
            // На реальных устройствах (API 28 и 31+) запись работает — пропускаем.
            throw org.junit.AssumptionViolatedException(
                "Нет аудио-входа в headless-эмуляторе ${api()}: пропускаем тест записи"
            )
        }
        assertTrue("start() должен пройти ${api()}: ${startResult.exceptionOrNull()}", startResult.isSuccess)

        Thread.sleep(2_000) // слишком короткая запись бросает на stop()

        val file = voiceRecorder.stop()
        assertNotNull("stop() вернул null ${api()}", file)
        assertTrue("Файл не существует ${api()}", file!!.exists())
        assertTrue("Пустой файл ${api()}", file.length() > 0)
        assertTrue("Ожидался M4A, получено: ${file.name} ${api()}", file.name.endsWith(".m4a"))
    }
}
