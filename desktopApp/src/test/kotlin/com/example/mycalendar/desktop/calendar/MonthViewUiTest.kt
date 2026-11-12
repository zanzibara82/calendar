package com.example.mycalendar.desktop.calendar

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.example.mycalendar.desktop.data.DesktopEventRepository
import com.example.mycalendar.desktop.data.EventFileStore
import com.example.mycalendar.desktop.theme.DesktopTheme
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.Event
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.runBlocking
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
    fun `clicking a day shows its date and holiday in the footer`() = runComposeUiTest {
        val repository = newRepository()
        setContent { DesktopTheme { MonthView(repository, initialMonth = november) } }

        onNodeWithTag("day-2026-11-04").performClick()

        onNodeWithText("4 ноября 2026 — День народного единства").assertExists()
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

    private fun monthTitle(month: YearMonth): String =
        month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.forLanguageTag("ru"))
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } +
            " " + month.year
}
