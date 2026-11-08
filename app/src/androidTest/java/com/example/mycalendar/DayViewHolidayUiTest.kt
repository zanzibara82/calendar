package com.example.mycalendar

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.holiday.DayMark
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.SettingsRepository
import com.example.mycalendar.presentation.components.DAY_HEADER_TAG
import com.example.mycalendar.presentation.components.HOLIDAY_MARKER_TAG
import com.example.mycalendar.presentation.components.MONTH_CELL_TAG
import com.example.mycalendar.presentation.components.PERIOD_NEXT_TAG
import com.example.mycalendar.presentation.components.PERIOD_PREV_TAG
import com.example.mycalendar.presentation.components.dayMarkTag
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Тонирование дней на живом экране: клетка месяца получает метку в теге, праздник —
 * форму-маркер, шапка дня — название праздника. Цвет пикселем не проверяется: тест сверяет
 * тег, то есть факт, что вид прочитал метку из состояния экрана.
 */
@LargeTest
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DayViewHolidayUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var authRepository: AuthRepository

    // 9 мая 2026 — суббота: тон праздника проверяется заодно с приоритетом над выходным.
    private val victoryDay: LocalDate = LocalDate.of(2026, 5, 9)

    // 5 мая 2026 — вторник без праздника; 16 мая 2026 — суббота без праздника.
    private val plainWeekday: LocalDate = LocalDate.of(2026, 5, 5)
    private val plainWeekend: LocalDate = LocalDate.of(2026, 5, 16)

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
    }

    /** Ожидание узла по тегу: состояние экрана приходит потоком, сразу после клика его ещё нет. */
    private fun awaitTag(tag: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun nodesWithText(text: String) =
        composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)

    /**
     * Узлы маркеров праздника. Маркер живёт внутри clickable-клетки, которая сливает
     * потомков в merged-дереве, поэтому считается только unmerged-дерево.
     */
    private fun holidayMarkerNodes() =
        composeRule.onAllNodes(hasTestTag(HOLIDAY_MARKER_TAG), useUnmergedTree = true).fetchSemanticsNodes()

    /**
     * Переводит выбор на клетку без метки (вторник 5 мая 2026) и дожидается шапки этого дня.
     * Нужно для счётчиков маркеров: стартовый выбор — `LocalDate.now()` (`CalendarViewModel`),
     * а на выбранной клетке маркер не рисуется (`CalendarScreen`), поэтому без фиксации счёт
     * зависел бы от даты устройства.
     */
    private fun pinSelectionToPlainDay() {
        composeRule.onNode(hasTestTag(dayMarkTag(MONTH_CELL_TAG, plainWeekday, null))).performClick()
        awaitTag(dayMarkTag(DAY_HEADER_TAG, plainWeekday, null))
    }

    private fun reachCalendar() {
        val res = composeRule.activity.resources
        val enter = res.getString(R.string.enter_app)
        val month = res.getString(R.string.view_month)
        composeRule.waitUntil(20_000) {
            nodesWithText(enter).fetchSemanticsNodes().isNotEmpty() ||
                nodesWithText(month).fetchSemanticsNodes().isNotEmpty()
        }
        if (nodesWithText(enter).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText(enter).performClick()
        }
        composeRule.waitUntil(20_000) {
            nodesWithText(month).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Переключение вида: название кнопки ищется точно, иначе «День» сливается с «День Победы». */
    private fun switchToView(label: String) = composeRule.onNodeWithText(label).performClick()

    /** Доезжает сеткой месяца до месяца целевой даты стрелками периодов. */
    private fun goToMonth(target: LocalDate) {
        reachCalendar()
        val months = ChronoUnit.MONTHS.between(LocalDate.now().withDayOfMonth(1), target.withDayOfMonth(1))
        val tag = if (months >= 0) PERIOD_NEXT_TAG else PERIOD_PREV_TAG
        repeat(kotlin.math.abs(months).toInt()) {
            composeRule.onNode(hasTestTag(tag)).performClick()
        }
    }

    @Test
    fun monthView_tintsHolidayCellAndShowsMarker() {
        goToMonth(victoryDay)
        val holidayTag = dayMarkTag(MONTH_CELL_TAG, victoryDay, DayMark.HOLIDAY)
        awaitTag(holidayTag)
        composeRule.onNode(hasTestTag(holidayTag)).assertExists()
        composeRule.waitUntil(10_000) {
            // Клетка clickable и сливает потомков в merged-дереве: маркер виден только в unmerged.
            composeRule.onAllNodes(hasTestTag(HOLIDAY_MARKER_TAG), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Природа хрупкости счётчиков маркеров — как у R28 в `WeekViewCardsUiTest`: выбор
     * по умолчанию это `LocalDate.now()`, а на выбранной клетке маркер скрыт правилом
     * «два признака в одной клетке шумят». При дате устройства 1 или 9 мая 2026 майская
     * сетка теряла бы один маркер ещё до всякого клика, поэтому оба теста ниже сначала
     * фиксируют выбор на дне без метки. Правильность таблицы дат — задача
     * `HolidayRegistryTest`, здесь проверяется поведение вида.
     */
    @Test
    fun monthView_marksOnlyHolidaysInTheGrid() {
        goToMonth(plainWeekend)
        awaitTag(dayMarkTag(MONTH_CELL_TAG, plainWeekend, DayMark.WEEKEND))
        pinSelectionToPlainDay()
        composeRule.onNode(hasTestTag(dayMarkTag(MONTH_CELL_TAG, plainWeekday, null))).assertExists()
        // В мае 2026 ровно два твёрдых праздника — 1 и 9 мая, и маркеров ровно столько.
        // Суббота 16 мая тонирована, но формы-признака не получает.
        composeRule.waitUntil(10_000) { holidayMarkerNodes().size == 2 }
        composeRule.onAllNodes(hasTestTag(HOLIDAY_MARKER_TAG), useUnmergedTree = true).assertCountEquals(2)
    }

    /**
     * Тот же приём, что у предыдущего теста: базовый счёт берётся при выборе на дне без
     * метки. Иначе при дате устройства 9 мая 2026 маркеров до клика был бы один, а после
     * клика ноль, и условие «ровно на один меньше» стало бы недостижимым для `waitUntil`.
     */
    @Test
    fun monthView_hidesMarkerOnSelectedCell() {
        goToMonth(victoryDay)
        val holidayTag = dayMarkTag(MONTH_CELL_TAG, victoryDay, DayMark.HOLIDAY)
        awaitTag(holidayTag)
        pinSelectionToPlainDay()
        // Базовый счёт снят при выборе на дне без метки: в мае 2026 помечены 1 и 9 число.
        composeRule.waitUntil(10_000) { holidayMarkerNodes().size == 2 }
        val markersBefore = holidayMarkerNodes().size
        assertEquals("до клика ожидаем два маркера", 2, markersBefore)
        composeRule.onNode(hasTestTag(holidayTag)).performClick()
        // Выбранная клетка залита акцентом: её маркер исчезает, а соседний праздник
        // (1 мая) остаётся — значит отключается именно выбор, а не маркеры вообще.
        // Ровно на один меньше, а не «меньше вообще»: базовый счёт не зависит от даты
        // устройства, поэтому единица после клика — это маркер 1 мая.
        composeRule.waitUntil(10_000) {
            holidayMarkerNodes().size == markersBefore - 1
        }
        assertEquals("на выбранной клетке остаётся один чужой маркер", 1, holidayMarkerNodes().size)
    }

    @Test
    fun dayView_showsHolidayTitleAndTint() {
        val res = composeRule.activity.resources
        goToMonth(victoryDay)
        val holidayTag = dayMarkTag(MONTH_CELL_TAG, victoryDay, DayMark.HOLIDAY)
        awaitTag(holidayTag)
        composeRule.onNode(hasTestTag(holidayTag)).performClick()
        switchToView(res.getString(R.string.view_day))

        composeRule.waitUntil(10_000) {
            nodesWithText(res.getString(R.string.holiday_victory_day)).fetchSemanticsNodes().isNotEmpty()
        }
        awaitTag(dayMarkTag(DAY_HEADER_TAG, victoryDay, DayMark.HOLIDAY))
    }

    @Test
    fun dayView_ordinaryWeekendHasTintButNoTitle() {
        val res = composeRule.activity.resources
        goToMonth(plainWeekend)
        val weekendTag = dayMarkTag(MONTH_CELL_TAG, plainWeekend, DayMark.WEEKEND)
        awaitTag(weekendTag)
        composeRule.onNode(hasTestTag(weekendTag)).performClick()
        switchToView(res.getString(R.string.view_day))
        awaitTag(dayMarkTag(DAY_HEADER_TAG, plainWeekend, DayMark.WEEKEND))
        composeRule.onNode(hasText(res.getString(R.string.holiday_victory_day), substring = false))
            .assertDoesNotExist()
    }

    @Test
    fun monthView_headerUnderGridHasTintButNoHolidayTitle() {
        // Спека §2 и уточнение 6 плана: название праздника — только в виде «День».
        // Под сеткой месяца шапка тонирована, но строки названия не получает.
        val res = composeRule.activity.resources
        goToMonth(victoryDay)
        val holidayTag = dayMarkTag(MONTH_CELL_TAG, victoryDay, DayMark.HOLIDAY)
        awaitTag(holidayTag)
        composeRule.onNode(hasTestTag(holidayTag)).performClick()
        // Тон шапки под сеткой проверяется тегом: он обязан появиться, иначе «нет строки»
        // означает просто неотработавшее тонирование.
        awaitTag(dayMarkTag(DAY_HEADER_TAG, victoryDay, DayMark.HOLIDAY))
        composeRule.onNode(hasText(res.getString(R.string.holiday_victory_day), substring = false))
            .assertDoesNotExist()
    }
}
