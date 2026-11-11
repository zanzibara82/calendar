package com.example.mycalendar

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.SettingsRepository
import com.example.mycalendar.presentation.components.ALARM_SOUND_ROW_TAG
import com.example.mycalendar.presentation.components.BIRTHDAY_SOUND_ROW_TAG
import com.example.mycalendar.presentation.components.QUIET_MODE_SWITCH_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Карточка «Напоминания»: тумблер тихого режима делает обе строки сигнала неактивными;
 * переключение с сохранённым выбором не стирает URI.
 */
@LargeTest
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsRemindersCardTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var authRepository: AuthRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching {
            ui.executeShellCommand("input keyevent KEYCODE_WAKEUP")
            ui.executeShellCommand("svc power stayon true")
            ui.executeShellCommand("wm dismiss-keyguard")
        }
        runBlocking {
            settingsRepository.setOnboardingCompleted(false)
            settingsRepository.setQuietMode(false)
            settingsRepository.setAlarmSoundUri(null)
            authRepository.signOutAll()
        }
        Thread.sleep(1_000)
    }

    private fun reachSettings() {
        val res = composeRule.activity.resources
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasText(res.getString(R.string.enter_app), substring = true),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodes(
                    androidx.compose.ui.test.hasText(res.getString(R.string.view_month), substring = true),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty()
        }
        val enter = composeRule.onAllNodes(
            androidx.compose.ui.test.hasText(res.getString(R.string.enter_app), substring = true),
            useUnmergedTree = true,
        )
        if (enter.fetchSemanticsNodes().isNotEmpty()) enter[0].performClick()
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasText(res.getString(R.string.view_month), substring = true),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(res.getString(R.string.settings)).performClick()
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasText(res.getString(R.string.calendars_section), substring = true),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun quietMode_disablesBothSoundRows() {
        reachSettings()
        composeRule.onNodeWithTag(QUIET_MODE_SWITCH_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ALARM_SOUND_ROW_TAG).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(BIRTHDAY_SOUND_ROW_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun togglingQuietMode_preservesSavedUri() {
        runBlocking { settingsRepository.setAlarmSoundUri("content://media/test/1") }
        reachSettings()
        composeRule.onNodeWithTag(QUIET_MODE_SWITCH_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(QUIET_MODE_SWITCH_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(
            "Сохранённый URI не стёрся переключением тихого режима",
            "content://media/test/1",
            runBlocking { settingsRepository.alarmSoundUri.first() },
        )
    }
}
