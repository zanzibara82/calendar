package com.example.mycalendar

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
 * Тихий канал существует с IMPORTANCE_MIN, без звука/вибрации/lights; существующий v2
 * сохраняет IMPORTANCE_HIGH — регрессия на «не трогать существующий канал».
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
    fun quietChannel_existsWithMinImportance() {
        val channel = manager.getNotificationChannel(NotificationHelper.CHANNEL_REMINDERS_QUIET)
        assertNotNull("Тихий канал должен быть создан", channel)
        assertEquals(NotificationManager.IMPORTANCE_MIN, channel!!.importance)
    }

    @Test
    fun quietChannel_hasNoSoundVibrationLights() {
        val channel = manager.getNotificationChannel(NotificationHelper.CHANNEL_REMINDERS_QUIET)!!
        assertNull("У тихого канала нет звука", channel.sound)
        assertFalse("Тихий канал не вибрирует", channel.shouldVibrate())
        assertFalse("Тихий канал без lights", channel.shouldShowLights())
    }

    @Test
    fun remindersChannel_keepsHighImportance() {
        val channel = manager.getNotificationChannel(NotificationHelper.CHANNEL_REMINDERS)
        assertNotNull(channel)
        assertEquals("Существующий v2 канал не тронут", NotificationManager.IMPORTANCE_HIGH, channel!!.importance)
    }
}
