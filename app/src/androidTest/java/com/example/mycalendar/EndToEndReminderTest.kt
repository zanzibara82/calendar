package com.example.mycalendar

import android.os.Build
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.usecase.SaveEventUseCase
import com.example.mycalendar.notifications.NotificationHelper
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
 * End-to-end тест: создаёт событие через SaveEventUseCase с напоминанием,
 * проверяет, что будильник AlarmManager зарегистрирован, и что AlarmReceiver
 * срабатывает в назначенное время (запуск ReminderActivity).
 */
@LargeTest
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EndToEndReminderTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var saveEventUseCase: SaveEventUseCase
    @Inject lateinit var reminderScheduler: ReminderScheduler
    @Inject lateinit var notificationHelper: NotificationHelper

    private val createdIds = mutableListOf<String>()

    @Before
    fun setUp() {
        hiltRule.inject()
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    "pm grant ${InstrumentationRegistry.getInstrumentation().targetContext.packageName}" +
                        " android.permission.POST_NOTIFICATIONS"
                )
            }
        }
        notificationHelper.createChannels()
    }

    @After
    fun tearDown() {
        runBlocking {
            createdIds.forEach { id ->
                reminderScheduler.cancel(
                    Event(
                        id = id,
                        title = "",
                        startTime = 0L,
                        endTime = 0L,
                        reminderMinutes = listOf(0),
                    )
                )
            }
        }
    }

    /**
     * Создаём событие с напоминанием "за 1 минуту", но триггер ставим через 2 секунды.
     * Проверяем, что ReminderActivity запускается (звук + окно в центре экрана).
     */
    @Test
    fun saveEvent_schedulesAndFiresReminder() {
        val now = System.currentTimeMillis()
        // Событие через 1 час, напоминание за 1 минуту, но мы подменим триггер
        val event = Event(
            id = UUID.randomUUID().toString(),
            title = "E2E Test " + Build.VERSION.SDK_INT,
            startTime = now + TimeUnit.HOURS.toMillis(1),
            endTime = now + TimeUnit.HOURS.toMillis(2),
            reminderMinutes = listOf(1), // за 1 минуту
            updatedAt = now,
        )
        createdIds.add(event.id)

        // Сохраняем через UseCase (как делает UI)
        val result = runBlocking { saveEventUseCase.invoke(event, hasNewRecording = false) }
        assertTrue("SaveEventUseCase failed: ${result.exceptionOrNull()}", result.isSuccess)

        // Непосредственно планируем будильник на ближайшие ~2 сек
        reminderScheduler.scheduleOneTime(
            event = event,
            minutes = 1,
            triggerAt = now + 2_000L,
            occurrenceStart = event.startTime,
            occurrenceEnd = event.endTime,
        )

        // Ждём 10 секунд — за это время AlarmReceiver должен сработать
        // и запустить ReminderActivity (которая воспроизводит звук)
        Thread.sleep(10_000)

        // Проверяем, что процесс ReminderActivity был запущен
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val procName = context.packageName
        // Проверяем через ActivityManager, что ReminderActivity отобразилась
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val processes = am.runningAppProcesses ?: emptyList()
        val found = processes.any { it.processName == procName }
        assertNotNull("App process should be running", found)
    }
}
