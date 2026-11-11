package com.example.mycalendar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.ReminderSound
import com.example.mycalendar.notifications.NotificationHelper
import com.example.mycalendar.notifications.ReminderActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Фабрика интента оверлея кладёт вид и URI сигнала в примитивные экстры. Оба пути
 * (AlarmReceiver и NotificationHelper) зовут одну фабрику — расхождение экстр невозможно.
 */
@RunWith(AndroidJUnit4::class)
class ReminderActivityIntentTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val event = Event(id = "intent-1", title = "Встреча", startTime = 1L, endTime = 2L)

    @Test
    fun silent_setsQuietAndNoUri() {
        val intent = ReminderActivity.createIntent(context, event, ReminderSound.Silent, "09:00")
        assertTrue(intent.getBooleanExtra(ReminderActivity.EXTRA_QUIET, false))
        assertNull(intent.getStringExtra(ReminderActivity.EXTRA_SOUND_URI))
        assertEquals("intent-1", intent.getStringExtra(NotificationHelper.EXTRA_EVENT_ID))
        assertEquals("Встреча", intent.getStringExtra(ReminderActivity.EXTRA_TITLE))
        assertEquals("09:00", intent.getStringExtra(ReminderActivity.EXTRA_TIME_TEXT))
    }

    @Test
    fun fromUri_setsUriAndNotQuiet() {
        val intent = ReminderActivity.createIntent(context, event, ReminderSound.FromUri("content://x/1"), "09:00")
        assertFalse(intent.getBooleanExtra(ReminderActivity.EXTRA_QUIET, true))
        assertEquals("content://x/1", intent.getStringExtra(ReminderActivity.EXTRA_SOUND_URI))
    }

    @Test
    fun builtin_setsNeitherQuietNorUri() {
        val intent = ReminderActivity.createIntent(context, event, ReminderSound.Builtin, "09:00")
        assertFalse(intent.getBooleanExtra(ReminderActivity.EXTRA_QUIET, true))
        assertNull(intent.getStringExtra(ReminderActivity.EXTRA_SOUND_URI))
    }
}
