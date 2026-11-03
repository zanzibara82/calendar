package com.example.mycalendar

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.domain.repository.SettingsRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Недельный вид показывает карточки по дням, а не плоский список: заголовок дня,
 * карточка события, тихая строка в пустой день.
 */
@LargeTest
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WeekViewCardsUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var eventRepository: EventRepository

    private val suffix = System.nanoTime().toString()
    private val title = "Недельная карточка $suffix"
    private val eventId = "week-card-$suffix"
    private val spanTitle = "Многосуточная недельная $suffix"
    private val spanEventId = "week-span-$suffix"

    @Before
    fun setUp() {
        hiltRule.inject()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching {
            ui.executeShellCommand("input keyevent KEYCODE_WAKEUP")
            ui.executeShellCommand("svc power stayon true")
            ui.executeShellCommand("wm dismiss-keyguard")
        }
        runCatching { runBlocking { settingsRepository.setOnboardingCompleted(false) } }
        runCatching { runBlocking { authRepository.signOutAll() } }
        Thread.sleep(1_000)

        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().atTime(9, 30).atZone(zone).toInstant().toEpochMilli()
        runBlocking {
            eventRepository.saveFromUser(
                Event(
                    id = eventId,
                    title = title,
                    startTime = start,
                    endTime = start + 45 * 60 * 1_000L,
                    reminderMinutes = listOf(15),
                    calendarProvider = CalendarProvider.LOCAL,
                )
            )
        }

        val weekMonday = LocalDate.now().with(DayOfWeek.MONDAY)
        val monStart = weekMonday.atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        runBlocking {
            eventRepository.saveFromUser(
                Event(
                    id = spanEventId,
                    title = spanTitle,
                    startTime = monStart,
                    endTime = monStart + 90 * 60 * 1_000L,
                    reminderMinutes = emptyList(),
                    calendarProvider = CalendarProvider.LOCAL,
                )
            )
        }
    }

    @After
    fun tearDown() {
        // Засеянные события не должны пережить прогон: даже если тест упал посередине,
        // висящая в базе карточка ломает ожидание пустого дня у следующих классов набора.
        runBlocking {
            eventRepository.hardDelete(eventId)
            eventRepository.hardDelete(spanEventId)
        }
    }

    private fun nodesWithText(text: String, substring: Boolean = true) =
        composeRule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true)

    private fun reachCalendar() {
        val res = composeRule.activity.resources
        val enter = res.getString(R.string.enter_app)
        composeRule.waitUntil(20_000) {
            nodesWithText(enter).fetchSemanticsNodes().isNotEmpty() ||
                nodesWithText(res.getString(R.string.view_month)).fetchSemanticsNodes().isNotEmpty()
        }
        if (nodesWithText(enter).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText(enter).performClick()
        }
        composeRule.waitUntil(20_000) {
            nodesWithText(res.getString(R.string.view_month)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun weekView_showsDayHeaderAndEventCard() {
        val res = composeRule.activity.resources
        reachCalendar()
        composeRule.onNodeWithText(res.getString(R.string.view_week)).performClick()

        // Сегодняшний заголовок: «Сегодня, 5 ноября»
        composeRule.waitUntil(10_000) {
            nodesWithText("Сегодня", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Карточка события — кликабельный узел с названием
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText(title) and hasClickAction())
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Карточка несёт время и провайдера
        assertTrue(nodesWithText("09:30").fetchSemanticsNodes().isNotEmpty())
        assertTrue(nodesWithText(res.getString(R.string.provider_short_local))
            .fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun weekView_emptyDayShowsQuietLine() {
        val res = composeRule.activity.resources
        reachCalendar()
        composeRule.onNodeWithText(res.getString(R.string.view_week)).performClick()
        composeRule.waitUntil(10_000) {
            nodesWithText(res.getString(R.string.no_events)).fetchSemanticsNodes().isNotEmpty()
        }
        // «Нет событий» — текст без действия клика
        composeRule.onAllNodes(hasText(res.getString(R.string.no_events)) and hasClickAction())
            .assertCountEquals(0)
    }

    @Test
    fun weekView_multiDayEventRendersUnderEachDay() {
        val res = composeRule.activity.resources
        reachCalendar()
        composeRule.onNodeWithText(res.getString(R.string.view_week)).performClick()
        composeRule.waitUntil(10_000) {
            nodesWithText(spanTitle).fetchSemanticsNodes().size >= 2
        }
        composeRule.onAllNodes(hasText(spanTitle) and hasClickAction()).assertCountEquals(2)
    }
}
