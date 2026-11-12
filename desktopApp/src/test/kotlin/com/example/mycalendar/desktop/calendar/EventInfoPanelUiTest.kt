package com.example.mycalendar.desktop.calendar

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.example.mycalendar.desktop.theme.DesktopTheme
import com.example.mycalendar.desktop.theme.ThemeMode
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.EventTag
import com.example.mycalendar.domain.model.RepeatRule
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class EventInfoPanelUiTest {

    private val zone = ZoneId.systemDefault()
    private val date = LocalDate.of(2026, 11, 14)

    @Test
    fun `shows title date and time`() = runComposeUiTest {
        setContent {
            DesktopTheme(ThemeMode.LIGHT) {
                EventInfoPanel(event = event(), date = date, zone = zone, onEdit = {}, onDelete = {}, onClose = {})
            }
        }

        onNodeWithText("Встреча с командой").assertExists()
        onNodeWithText("Суббота, 14 ноября 2026").assertExists()
        onNodeWithText("09:00–10:00").assertExists()
    }

    @Test
    fun `shows repeat description note and tags when present`() = runComposeUiTest {
        setContent {
            DesktopTheme(ThemeMode.LIGHT) {
                EventInfoPanel(
                    event = event().copy(
                        repeatRule = RepeatRule.WEEKLY,
                        description = "Обсудить релиз",
                        textNote = "Взять ноутбук",
                        tags = listOf(EventTag.DATE, EventTag.CINEMA),
                    ),
                    date = date,
                    zone = zone,
                    onEdit = {},
                    onDelete = {},
                    onClose = {},
                )
            }
        }

        onNodeWithText("Еженедельно").assertExists()
        onNodeWithText("Обсудить релиз").assertExists()
        onNodeWithText("Взять ноутбук").assertExists()
        onNodeWithText("Свидание").assertExists()
        onNodeWithText("Кино").assertExists()
        onNodeWithText("День рождения").assertDoesNotExist()
    }

    @Test
    fun `open bound is shown as ellipsis`() = runComposeUiTest {
        val night = event(
            start = LocalDateTime.of(2026, 11, 13, 23, 0),
            end = LocalDateTime.of(2026, 11, 14, 1, 0),
        )
        setContent {
            DesktopTheme(ThemeMode.LIGHT) {
                EventInfoPanel(event = night, date = date, zone = zone, onEdit = {}, onDelete = {}, onClose = {})
            }
        }

        onNodeWithText("…–01:00").assertExists()
    }

    @Test
    fun `edit delete and close buttons report`() = runComposeUiTest {
        var edited: Event? = null
        var deleted: Event? = null
        var closed = false
        val sample = event()
        setContent {
            DesktopTheme(ThemeMode.LIGHT) {
                EventInfoPanel(
                    event = sample,
                    date = date,
                    zone = zone,
                    onEdit = { edited = it },
                    onDelete = { deleted = it },
                    onClose = { closed = true },
                )
            }
        }

        onNodeWithTag("event-info-edit").performClick()
        onNodeWithTag("event-info-delete").performClick()
        onNodeWithTag("event-info-close").performClick()

        assertEquals("a", edited?.id)
        assertEquals("a", deleted?.id)
        assertTrue(closed)
    }

    private fun event(
        start: LocalDateTime = LocalDateTime.of(2026, 11, 14, 9, 0),
        end: LocalDateTime = LocalDateTime.of(2026, 11, 14, 10, 0),
    ): Event = Event(
        id = "a",
        title = "Встреча с командой",
        startTime = start.atZone(zone).toInstant().toEpochMilli(),
        endTime = end.atZone(zone).toInstant().toEpochMilli(),
    )
}
