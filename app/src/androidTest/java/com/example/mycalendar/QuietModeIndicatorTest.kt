package com.example.mycalendar

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.SettingsRepository
import com.example.mycalendar.presentation.components.QUIET_INDICATOR_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Индикатор NotificationsOff виден только при активном тихом режиме; тап выключает режим.
 * Включается режим только из настроек — здесь он задаётся напрямую через репозиторий.
 */
@LargeTest
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class QuietModeIndicatorTest {

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
            settingsRepository.setQuietMode(true)
            authRepository.signOutAll()
        }
        Thread.sleep(1_000)
    }

    @Test
    fun indicator_visibleWhenQuiet_tapTurnsOff() {
        val res = composeRule.activity.resources
        // Добираемся до календаря (стартовый экран → «Войти»).
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
        // Индикатор на месте, тап выключает режим.
        composeRule.onNodeWithTag(QUIET_INDICATOR_TAG).performClick()
        composeRule.waitUntil(10_000) {
            runBlocking { settingsRepository.quietMode.first() } == false
        }
        assertFalse(runBlocking { settingsRepository.quietMode.first() })
    }
}
