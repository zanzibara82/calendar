package com.example.mycalendar

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.SyncSnapshot
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.ConflictRepository
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.domain.repository.SettingsRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Экраны разбора конфликта на живом Compose: карточка в настройках открывает очередь,
 * очередь — разбор, выбор облачного значения применяется и закрывает конфликт.
 *
 * Конфликт засевается прямо в репозитории: сеть для этого не нужна, проверяется UI-путь
 * «настройки → очередь → разбор → запись».
 */
@LargeTest
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ConflictScreensUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var eventRepository: EventRepository

    @Inject
    lateinit var conflictRepository: ConflictRepository

    private val suffix = System.nanoTime().toString()
    private val localTitle = "Локальная правка $suffix"
    private val cloudTitle = "Версия из облака $suffix"
    private val eventId = "ui-conflict-event-$suffix"
    private val conflictId = "ui-conflict-$suffix"

    @Before
    fun setUp() {
        hiltRule.inject()

        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching {
            ui.executeShellCommand("input keyevent KEYCODE_WAKEUP")
            ui.executeShellCommand("svc power stayon true")
            ui.executeShellCommand("wm dismiss-keyguard")
        }
        // Общий DataStore: онбординг и подключённые календари могли остаться от других тестов.
        runCatching { runBlocking { settingsRepository.setOnboardingCompleted(false) } }
        runCatching { runBlocking { authRepository.signOutAll() } }
        Thread.sleep(1_000)

        runBlocking {
            eventRepository.saveFromUser(
                Event(
                    id = eventId,
                    title = localTitle,
                    description = "Описание",
                    startTime = 1_000L,
                    endTime = 2_000L,
                    calendarProvider = CalendarProvider.LOCAL,
                )
            )
            conflictRepository.save(
                SyncConflict(
                    id = conflictId,
                    eventId = eventId,
                    provider = CalendarProvider.LOCAL,
                    remoteId = "remote-$suffix",
                    kind = ConflictKind.CONTENT,
                    base = SyncSnapshot(
                        title = "Общее название",
                        description = "Описание",
                        startTime = 1_000L,
                        endTime = 2_000L,
                        reminderMinutes = emptyList(),
                        repeatRule = null,
                    ),
                    remote = SyncSnapshot(
                        title = cloudTitle,
                        description = "Описание",
                        startTime = 1_000L,
                        endTime = 2_000L,
                        reminderMinutes = emptyList(),
                        repeatRule = null,
                    ),
                    contested = setOf(SyncField.TITLE),
                    detectedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    @After
    fun tearDown() {
        // Строка и будильники не должны пережить прогон, даже если тест упал посередине.
        runBlocking {
            conflictRepository.delete(conflictId)
            eventRepository.hardDelete(eventId)
        }
    }

    @Test
    fun conflictFromSettings_chooseCloudValue_appliesAndCloses() {
        val res = composeRule.activity.resources
        reachCalendar(res)

        // Настройки → карточка конфликтов (счётчик появился из-за засеянного конфликта).
        composeRule.onNodeWithContentDescription(res.getString(R.string.settings)).performClick()
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText(res.getString(R.string.calendars_section))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Счётчик приходит из Flow и может появиться на кадр позже самого экрана.
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText("Конфликты:", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(hasScrollAction(), useUnmergedTree = true)[0]
            .performScrollToNode(hasText("Конфликты:", substring = true))
        composeRule.onNode(hasText("Конфликты:", substring = true)).performClick()

        // Очередь: карточка названа облачным значением, переход — по ней.
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText(res.getString(R.string.conflicts_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(20_000) {
            runCatching {
                composeRule.onNode(hasText(cloudTitle) and hasClickAction()).assertExists()
            }.isSuccess
        }
        composeRule.onNode(hasText(cloudTitle) and hasClickAction()).performClick()

        // Разбор по полям: по умолчанию выбрано локальное, переключаемся на облачное.
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText(res.getString(R.string.conflict_detail_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Локальное: $localTitle").assertExists()
        composeRule.onNodeWithText("Из облака: $cloudTitle").assertExists()
        composeRule.onNode(hasText(res.getString(R.string.conflict_use_cloud)) and hasClickAction())
            .performClick()
        composeRule.onNode(hasText(res.getString(R.string.conflict_apply)) and hasClickAction())
            .performClick()

        // Экран сам закрывается: очередь больше не показывает этот конфликт.
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText(cloudTitle).fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitUntil(20_000) {
            runBlocking { conflictRepository.get(conflictId) } == null
        }
        assertEquals(cloudTitle, runBlocking { eventRepository.getEvent(eventId) }?.title)
        assertNull(runBlocking { conflictRepository.get(conflictId) })

        // Возврат из очереди — снова настройки, а не пустой экран.
        composeRule.onNodeWithContentDescription(res.getString(R.string.back)).performClick()
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText(res.getString(R.string.calendars_section))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Гарантированно попасть на календарь из любого стартового состояния: Splash мог
     * пропустить вход (флаг онбординга общий для процесса), тогда «Войти» уже нет.
     */
    private fun reachCalendar(res: android.content.res.Resources) {
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText(res.getString(R.string.enter_app), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText(res.getString(R.string.view_month), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        repeat(3) {
            val enter = composeRule.onAllNodesWithText(
                res.getString(R.string.enter_app),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            if (enter.isEmpty()) return@repeat
            composeRule.onAllNodesWithText(
                res.getString(R.string.enter_app),
                useUnmergedTree = true,
            )[0].performClick()
            Thread.sleep(800)
        }
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText(res.getString(R.string.view_month), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
