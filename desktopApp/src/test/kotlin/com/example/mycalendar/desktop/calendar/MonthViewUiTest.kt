package com.example.mycalendar.desktop.calendar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.example.mycalendar.desktop.data.DesktopEventRepository
import com.example.mycalendar.desktop.data.EventFileStore
import com.example.mycalendar.desktop.theme.DesktopTheme
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalTestApi::class)
class MonthViewUiTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val november = YearMonth.of(2026, 11)

    @Test
    fun `header shows month and weekday names`() = runComposeUiTest {
        val repository = newRepository()
        setContent { DesktopTheme { MonthView(repository, initialMonth = november) } }

        onNodeWithText("Ноябрь 2026").assertExists()
        onNodeWithText("Пн").assertExists()
        onNodeWithText("Вс").assertExists()
    }

    @Test
    fun `arrows change visible month`() = runComposeUiTest {
        val repository = newRepository()
        setContent { DesktopTheme { MonthView(repository, initialMonth = november) } }

        onNodeWithText("›").performClick()
        onNodeWithText("Декабрь 2026").assertExists()

        onNodeWithText("‹").performClick()
        onNodeWithText("Ноябрь 2026").assertExists()
    }

    @Test
    fun `today button returns to current month`() = runComposeUiTest {
        val repository = newRepository()
        setContent { DesktopTheme { MonthView(repository, initialMonth = YearMonth.of(2020, 1)) } }

        onNodeWithText("Сегодня").performClick()

        onNodeWithText(monthTitle(YearMonth.now())).assertExists()
    }

    @Test
    fun `clicking a day shows its date and holiday in the panel header`() = runComposeUiTest {
        val repository = newRepository()
        setContent { DesktopTheme { MonthView(repository, initialMonth = november) } }

        onNodeWithTag("day-2026-11-04").performClick()

        onNodeWithText("Среда, 4 ноября 2026 — День народного единства").assertExists()
    }

    @Test
    fun `holiday has shape marker and regular day does not`() = runComposeUiTest {
        val repository = newRepository()
        setContent { DesktopTheme { MonthView(repository, initialMonth = november) } }

        onNodeWithTag("holiday-marker-2026-11-04", useUnmergedTree = true).assertExists()
        onNodeWithTag("holiday-marker-2026-11-05", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("holiday-marker-2026-11-07", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `legend explains day tones`() = runComposeUiTest {
        val repository = newRepository()
        setContent { DesktopTheme { MonthView(repository, initialMonth = november) } }

        onNodeWithText("Выходной").assertExists()
        onNodeWithText("Праздник").assertExists()
    }

    @Test
    fun `day cells show event dots with a cap of three`() = runComposeUiTest {
        val repository = newRepository()
        runBlocking {
            repeat(4) { index ->
                repository.saveFromUser(
                    event("e$index", LocalDate.of(2026, 11, 4), 9 + index, CalendarProvider.LOCAL),
                )
            }
            repository.saveFromUser(
                event("f", LocalDate.of(2026, 11, 5), 12, CalendarProvider.YANDEX),
            )
        }
        setContent { DesktopTheme { MonthView(repository, initialMonth = november) } }

        waitUntil("точки событий загружены", 5_000) {
            onAllNodesWithTag("event-dot-2026-11-05-0", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        onNodeWithTag("event-dot-2026-11-04-2", useUnmergedTree = true).assertExists()
        onNodeWithTag("event-dot-2026-11-04-3", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("event-dot-2026-11-06-0", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `no events means no dots`() = runComposeUiTest {
        val repository = newRepository()
        setContent { DesktopTheme { MonthView(repository, initialMonth = november) } }

        onNodeWithTag("event-dot-2026-11-04-0", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `day panel lists events of the selected day with times`() = runComposeUiTest {
        val repository = newRepository()
        runBlocking {
            repository.saveFromUser(event("a", LocalDate.of(2026, 11, 10), 9, CalendarProvider.LOCAL))
            repository.saveFromUser(event("b", LocalDate.of(2026, 11, 10), 18, CalendarProvider.GOOGLE))
        }
        setContent { DesktopTheme { MonthView(repository, initialMonth = november) } }

        onNodeWithTag("day-2026-11-10").performClick()
        waitUntil("события дня загружены", 5_000) {
            onAllNodesWithText("Событие a").fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithText("Событие b").assertExists()
        onNodeWithText("09:00–10:00").assertExists()
        onNodeWithText("18:00–19:00").assertExists()
    }

    @Test
    fun `empty day says there are no events`() = runComposeUiTest {
        val repository = newRepository()
        runBlocking {
            repository.saveFromUser(event("a", LocalDate.of(2026, 11, 10), 9, CalendarProvider.LOCAL))
        }
        setContent { DesktopTheme { MonthView(repository, initialMonth = november) } }

        onNodeWithTag("day-2026-11-05").performClick()

        onNodeWithText("Событий нет").assertExists()
    }

    @Test
    fun `switching day updates the list`() = runComposeUiTest {
        val repository = newRepository()
        runBlocking {
            repository.saveFromUser(event("a", LocalDate.of(2026, 11, 10), 9, CalendarProvider.LOCAL))
        }
        setContent { DesktopTheme { MonthView(repository, initialMonth = november) } }

        onNodeWithTag("day-2026-11-10").performClick()
        waitUntil("событие показано", 5_000) {
            onAllNodesWithText("Событие a").fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithTag("day-2026-11-05").performClick()

        onNodeWithText("Событие a").assertDoesNotExist()
        onNodeWithText("Событий нет").assertExists()
    }

    @Test
    fun `multi-day event shows open start on the following day`() = runComposeUiTest {
        val repository = newRepository()
        runBlocking {
            repository.saveFromUser(
                event(
                    id = "night",
                    start = LocalDateTime.of(2026, 11, 9, 23, 0),
                    end = LocalDateTime.of(2026, 11, 10, 1, 0),
                ),
            )
        }
        setContent { DesktopTheme { MonthView(repository, initialMonth = november) } }

        onNodeWithTag("day-2026-11-10").performClick()
        waitUntil("ночное событие показано", 5_000) {
            onAllNodesWithText("Событие night").fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithText("…–01:00").assertExists()
    }

    @Test
    fun `repeating event shows its repeat label`() = runComposeUiTest {
        val repository = newRepository()
        runBlocking {
            repository.saveFromUser(
                event(
                    id = "weekly",
                    start = LocalDateTime.of(2026, 11, 3, 9, 0),
                    end = LocalDateTime.of(2026, 11, 3, 10, 0),
                    repeatRule = RepeatRule.WEEKLY,
                ),
            )
        }
        setContent { DesktopTheme { MonthView(repository, initialMonth = november) } }

        onNodeWithTag("day-2026-11-10").performClick()
        waitUntil("повторяющееся событие показано", 5_000) {
            onAllNodesWithText("Событие weekly").fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithText("09:00–10:00 · еженедельно").assertExists()
    }

    @Test
    fun `gear button opens settings`() = runComposeUiTest {
        val repository = newRepository()
        var opened = false
        setContent {
            DesktopTheme { MonthView(repository, initialMonth = november, onOpenSettings = { opened = true }) }
        }

        onNodeWithTag("open-settings").performClick()

        org.junit.Assert.assertTrue(opened)
    }

    @Test
    fun `add button reports the selected day`() = runComposeUiTest {
        val repository = newRepository()
        var requested: LocalDate? = null
        setContent {
            DesktopTheme {
                MonthView(repository, initialMonth = november, onAddEvent = { requested = it })
            }
        }

        onNodeWithTag("day-2026-11-04").performClick()
        onNodeWithTag("add-event").performClick()

        assertEquals(LocalDate.of(2026, 11, 4), requested)
    }

    @Test
    fun `clicking an event card reports the event for details`() = runComposeUiTest {
        val repository = newRepository()
        var clicked: Event? = null
        runBlocking {
            repository.saveFromUser(event("a", LocalDate.of(2026, 11, 10), 9, CalendarProvider.LOCAL))
        }
        setContent {
            DesktopTheme {
                MonthView(repository, initialMonth = november, onEventClick = { clicked = it })
            }
        }

        onNodeWithTag("day-2026-11-10").performClick()
        waitUntil("событие дня показано", 5_000) {
            onAllNodesWithTag("day-event-a", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("day-event-a", useUnmergedTree = true).performClick()

        assertEquals("a", clicked?.id)
    }

    @Test
    fun `selected event opens the info panel on the right`() = runComposeUiTest {
        val repository = newRepository()
        val sample = event("a", LocalDate.of(2026, 11, 10), 9, CalendarProvider.LOCAL)
        var info by mutableStateOf<Event?>(null)
        runBlocking { repository.saveFromUser(sample) }
        setContent {
            DesktopTheme {
                MonthView(
                    repository = repository,
                    initialMonth = november,
                    infoEvent = info,
                    onEventClick = { info = it },
                )
            }
        }

        onNodeWithTag("day-2026-11-10").performClick()
        waitUntil("событие дня показано", 5_000) {
            onAllNodesWithTag("day-event-a", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("event-info").assertDoesNotExist()

        onNodeWithTag("day-event-a", useUnmergedTree = true).performClick()

        onNodeWithTag("event-info").assertExists()
        onNodeWithTag("event-info-title", useUnmergedTree = true).assertTextEquals("Событие a")
    }

    @Test
    fun `info panel edit opens the editor callback`() = runComposeUiTest {
        val repository = newRepository()
        val sample = event("a", LocalDate.of(2026, 11, 10), 9, CalendarProvider.LOCAL)
        var edited: Event? = null
        runBlocking { repository.saveFromUser(sample) }
        setContent {
            DesktopTheme {
                MonthView(
                    repository = repository,
                    initialMonth = november,
                    infoEvent = sample,
                    onEditEvent = { edited = it },
                )
            }
        }

        onNodeWithTag("event-info-edit").performClick()

        assertEquals("a", edited?.id)
    }

    private fun newRepository(): DesktopEventRepository {
        val dir = folder.newFolder().toPath()
        return DesktopEventRepository(EventFileStore(dir.resolve("events.json")))
    }

    private fun event(id: String, day: LocalDate, hour: Int, provider: CalendarProvider): Event {
        val start = day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Event(
            id = id,
            title = "Событие $id",
            startTime = start,
            endTime = start + 3_600_000,
            calendarProvider = provider,
        )
    }

    private fun event(
        id: String,
        start: LocalDateTime,
        end: LocalDateTime,
        repeatRule: RepeatRule? = null,
    ): Event {
        val zone = ZoneId.systemDefault()
        return Event(
            id = id,
            title = "Событие $id",
            startTime = start.atZone(zone).toInstant().toEpochMilli(),
            endTime = end.atZone(zone).toInstant().toEpochMilli(),
            repeatRule = repeatRule,
        )
    }

    private fun monthTitle(month: YearMonth): String =
        month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.forLanguageTag("ru"))
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } +
            " " + month.year
}
