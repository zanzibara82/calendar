package com.example.mycalendar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.notifications.NotificationHelper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Тихий канал существует с IMPORTANCE_HIGH (иначе система не доставит full-screen intent),
 * но без звука/вибрации/lights; существующий v2 сохраняет IMPORTANCE_HIGH — регрессия
 * на «не трогать существующий канал».
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class QuietModeChannelTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var notificationHelper: NotificationHelper

    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        hiltRule.inject()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationHelper.createChannels()
    }

    @Test
    fun quietChannel_existsWithHighImportance() {
        val channel = manager.getNotificationChannel(NotificationHelper.CHANNEL_REMINDERS_QUIET)
        assertNotNull("Тихий канал должен быть создан", channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel!!.importance)
    }

    @Test
    fun quietChannel_hasNoSoundVibrationLights() {
        val channel = manager.getNotificationChannel(NotificationHelper.CHANNEL_REMINDERS_QUIET)!!
        assertNull("У тихого канала нет звука", channel.sound)
        assertFalse("Тихий канал не вибрирует", channel.shouldVibrate())
        assertFalse("Тихий канал без lights", channel.shouldShowLights())
    }

    /**
     * Импортанс канала неизменяем после создания, поэтому канал прежней сборки
     * (id без `_v2`, IMPORTANCE_MIN) должен быть удалён, а новый создан с HIGH —
     * иначе full-screen intent останется подавленным на обновившейся установке.
     */
    @Test
    fun legacyQuietChannel_withMin_isReplacedByHighVersion() {
        manager.createNotificationChannel(
            NotificationChannel(
                "channel_event_reminders_quiet",
                "тихий режим (прежняя сборка)",
                NotificationManager.IMPORTANCE_MIN,
            ),
        )
        notificationHelper.createChannels()
        val channel = manager.getNotificationChannel(NotificationHelper.CHANNEL_REMINDERS_QUIET)
        assertEquals(
            "Новый тихий канал должен создаться с IMPORTANCE_HIGH",
            NotificationManager.IMPORTANCE_HIGH,
            channel!!.importance,
        )
        assertNull(
            "Прежний канал с IMPORTANCE_MIN должен быть удалён",
            manager.getNotificationChannel("channel_event_reminders_quiet"),
        )
    }

    @Test
    fun remindersChannel_keepsHighImportance() {
        val channel = manager.getNotificationChannel(NotificationHelper.CHANNEL_REMINDERS)
        assertNotNull(channel)
        assertEquals("Существующий v2 канал не тронут", NotificationManager.IMPORTANCE_HIGH, channel!!.importance)
    }
}
