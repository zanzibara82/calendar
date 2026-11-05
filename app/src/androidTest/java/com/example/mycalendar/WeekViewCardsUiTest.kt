package com.example.mycalendar

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.EventRepository
import com.example.mycalendar.domain.repository.SettingsRepository
import com.example.mycalendar.presentation.common.russianLocale
import com.example.mycalendar.presentation.common.weekColDateFormatter
import com.example.mycalendar.presentation.common.weekdayTitleFormatter
import com.example.mycalendar.presentation.theme.AppSizes
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
 * Недельный вид — это семь карточек дней в одном столбце, а не плоский список: строка
 * заголовка с датой, компактные строки событий в одну линию (без чипов), пустой день —
 * карточка только с заголовком и «+N ещё» с переходом в вид «День».
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

    // Дата берётся один раз на экземпляр теста: setUp и тело теста обязаны смотреть в один и тот
    // же день, иначе прогон через полночь разъедется с ожидаемым «+N ещё».
    private val today = LocalDate.now()
    private val weekMonday = today.with(DayOfWeek.MONDAY)

    // Посевов стало больше (шесть событий на «+N ещё»), поэтому id накапливаются в списке:
    // только так tearDown удалит все — висящая в базе карточка ломает ожидание пустого
    // дня у следующего теста.
    private val seededIds = mutableListOf<String>()

    private fun seed(id: String, eventTitle: String, start: Long, durationMinutes: Long = 45) {
        runBlocking {
            eventRepository.saveFromUser(
                Event(
                    id = id,
                    title = eventTitle,
                    startTime = start,
                    endTime = start + durationMinutes * 60 * 1_000L,
                    reminderMinutes = emptyList(),
                    calendarProvider = CalendarProvider.LOCAL,
                )
            )
        }
        seededIds += id
    }

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
        val start = today.atTime(9, 30).atZone(zone).toInstant().toEpochMilli()
        seed(eventId, title, start)

        val monStart = weekMonday.atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        seed(spanEventId, spanTitle, monStart, durationMinutes = 90)
    }

    @After
    fun tearDown() {
        // Засеянные события не должны пережить прогон: даже если тест упал посередине,
        // висящая в базе карточка ломает ожидание пустого дня у следующих классов набора.
        runBlocking {
            seededIds.forEach { eventRepository.hardDelete(it) }
        }
    }

    private fun nodesWithText(text: String, substring: Boolean = true) =
        composeRule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true)

    /** Узлы с точным текстом в неразъеденном дереве: заголовок не должен сливаться с телом. */
    private fun headerNodes(text: String) =
        composeRule.onAllNodes(hasText(text, substring = false), useUnmergedTree = true)

    /**
     * Дни недели, свободные от посевов setUp: понедельник и вторник заняты многосуточным
     * событием, сегодняшний день — основным. Кандидатов не меньше четырёх в любой день недели,
     * поэтому тесты берут из этого списка разные концы и не затирают карточку друг друга.
     */
    private fun freeWeekDays(): List<LocalDate> =
        (0L..6L).map { weekMonday.plusDays(it) }
            .filter { it != today && it != weekMonday && it != weekMonday.plusDays(1) }

    /**
     * Прокручивает список недели к карточке дня. Карточки ниже сгиба экрана в ленивом списке
     * не композятся, поэтому без прокрутки проверка зависела бы от высоты экрана и размера
     * системного шрифта.
     */
    private fun scrollToWeekCard(date: LocalDate) {
        composeRule.onAllNodes(hasScrollToIndexAction()).onFirst()
            .performScrollToIndex(date.dayOfWeek.value - 1)
    }

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
    fun weekView_showsDayCardWithCompactCell() {
        val res = composeRule.activity.resources
        reachCalendar()
        composeRule.onNodeWithText(res.getString(R.string.view_week)).performClick()

        // Строка заголовка сегодняшней карточки: дата вида «05.11»
        val todayLabel = today.format(weekColDateFormatter)
        scrollToWeekCard(today)
        composeRule.waitUntil(10_000) {
            nodesWithText(todayLabel, substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        // Компактная строка: название кликабельно, время начала на месте
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText(title) and hasClickAction())
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(nodesWithText("09:30").fetchSemanticsNodes().isNotEmpty())
        // Чипов в компактной строке нет: это единственное отличие от карточки вида «День»
        composeRule.onAllNodes(hasText(res.getString(R.string.provider_short_local)))
            .assertCountEquals(0)
    }

    @Test
    fun weekView_emptyDayCardShowsNoQuietLine() {
        val res = composeRule.activity.resources
        reachCalendar()
        composeRule.onNodeWithText(res.getString(R.string.view_week)).performClick()
        // Пустой день — карточка без тела: тихой строки в недельном виде больше нет.
        // Проверка снимается, пока список вверху: там композится максимум карточек.
        composeRule.onAllNodes(hasText(res.getString(R.string.no_events)))
            .assertCountEquals(0)
        // Карточка сегодняшнего дня может лежать ниже сгиба, поэтому время ждём после
        // прокрутки к ней.
        scrollToWeekCard(today)
        composeRule.waitUntil(10_000) {
            nodesWithText("09:30").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun weekView_emptyDayCardShowsHeaderOnly() {
        // Начало свободного окна: тест «+N ещё» берёт его конец, поэтому посевы того теста
        // никогда не попадают в карточку, которую этот тест объявляет пустой.
        val emptyDay = freeWeekDays().first()
        val res = composeRule.activity.resources
        reachCalendar()
        composeRule.onNodeWithText(res.getString(R.string.view_week)).performClick()

        val emptyLabel = emptyDay.format(weekColDateFormatter)
        scrollToWeekCard(emptyDay)
        composeRule.waitUntil(10_000) {
            nodesWithText(emptyLabel, substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        // Дата пустого дня стоит ровно один раз — только в строке заголовка, тела у карточки нет.
        headerNodes(emptyLabel).assertCountEquals(1)
        // Дата пустой карточки не кликабельна: если клик повесить на весь заголовок, строка
        // заголовка в объединённом дереве сливается в один clickable-узел и проверка упадёт.
        composeRule.onAllNodes(hasText(emptyLabel) and hasClickAction())
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

    @Test
    fun weekView_moreThanFourEventsShowsMoreLineAndOpensDay() {
        val zone = ZoneId.systemDefault()
        // Сеять «многие» события на сегодня нельзя: в понедельник и вторник в карточку
        // «сегодня» попадает ещё и многосуточный посев setUp, и скрытых строк стало бы не
        // две, а три. Берём конец списка свободных дней: начало списка занято тестом пустой
        // карточки, поэтому посевы двух тестов не пересекаются.
        val busyDay = freeWeekDays().last()
        val dayStart = busyDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val extra = 2
        repeat(AppSizes.weekVisibleCells + extra) { index ->
            seed(
                id = "week-many-$suffix-$index",
                eventTitle = "Много $suffix $index",
                start = dayStart + (6L + index) * 60 * 60 * 1_000L,
            )
        }
        val res = composeRule.activity.resources
        reachCalendar()
        composeRule.onNodeWithText(res.getString(R.string.view_week)).performClick()

        val moreText = res.getString(R.string.week_more_events, extra)
        scrollToWeekCard(busyDay)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText(moreText) and hasClickAction())
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(hasText(moreText) and hasClickAction()).assertCountEquals(1)
        // Видны ровно weekVisibleCells строк, остальные спрятаны за «+N ещё»
        nodesWithText("Много $suffix").assertCountEquals(AppSizes.weekVisibleCells)
        // Тап по «+N ещё» открывает вид «День» на этом же дне
        val moreNode = composeRule.onAllNodes(hasText(moreText) and hasClickAction()).onFirst()
        moreNode.performScrollTo()
        moreNode.performClick()
        val dayHeader = busyDay.format(weekdayTitleFormatter)
            .replaceFirstChar { it.uppercase(russianLocale) }
        composeRule.waitUntil(10_000) {
            nodesWithText(dayHeader).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
