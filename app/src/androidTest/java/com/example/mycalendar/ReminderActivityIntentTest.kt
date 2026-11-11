package com.example.mycalendar

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.ReminderSound
import com.example.mycalendar.notifications.NotificationHelper
import com.example.mycalendar.notifications.ReminderActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Фабрика интента оверлея кладёт вид и URI сигнала в примитивные экстры. Оба пути
 * (AlarmReceiver и NotificationHelper) зовут одну фабрику — расхождение экстр невозможно.
 * Отдельно проверяется повторный intent: оверлей перечитывает экстры и перезапускает
 * звук под новое событие.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ReminderActivityIntentTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val event = Event(id = "intent-1", title = "Встреча", startTime = 1L, endTime = 2L)

    @Before
    fun setUp() {
        hiltRule.inject()
    }

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

    /**
     * Повторный intent от нового события: экстры перечитываются, «Отложить» получит новый
     * eventId, а прежний звук и вибрация гасятся — тихий режим не поднимает MediaPlayer.
     */
    @Test
    fun newIntent_withNewSilentEvent_stopsSoundAndUpdatesEventId() {
        val first = ReminderActivity.createIntent(context, event, ReminderSound.Builtin, "09:00")
        ActivityScenario.launch<ReminderActivity>(first).use { scenario ->
            val next = Event(id = "intent-2", title = "Позже", startTime = 3L, endTime = 4L)
            val second = ReminderActivity.createIntent(context, next, ReminderSound.Silent, "10:00")
            scenario.onActivity { activity ->
                activity.handleNewIntent(second)
                assertEquals("intent-2", activity.currentEventIdForTest())
                assertFalse(
                    "После тихого интента MediaPlayer не играет",
                    activity.soundPlayingForTest(),
                )
            }
        }
    }
}
