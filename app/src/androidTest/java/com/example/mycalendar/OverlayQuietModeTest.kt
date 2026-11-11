package com.example.mycalendar

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.ReminderSound
import com.example.mycalendar.notifications.ReminderActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Тихий оверлей не поднимает MediaPlayer и не падает там, где hasVibrator() == false
 * (headless-эмулятор). Запуск с кастомным интентом — через ActivityScenario, без compose-rule.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OverlayQuietModeTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setUp() {
        hiltRule.inject()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching {
            ui.executeShellCommand("input keyevent KEYCODE_WAKEUP")
            ui.executeShellCommand("svc power stayon true")
            ui.executeShellCommand("wm dismiss-keyguard")
        }
    }

    @Test
    fun quietOverlay_doesNotCreateMediaPlayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val event = Event(id = "quiet-1", title = "Тихое напоминание", startTime = 1L, endTime = 2L)
        val intent = ReminderActivity.createIntent(context, event, ReminderSound.Silent, "09:00")
        ActivityScenario.launch<ReminderActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("В тихом режиме MediaPlayer не создаётся", activity.soundPlayingForTest())
            }
        }
    }
}
