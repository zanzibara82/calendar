package com.example.mycalendar.desktop.calendar

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.example.mycalendar.desktop.theme.DesktopTheme
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class EventEditorUiTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val day = LocalDate.of(2026, 11, 12)

    private fun millis(hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun existingEvent() = Event(
        id = "e1",
        title = "Планёрка",
        startTime = millis(9),
        endTime = millis(10),
        repeatRule = RepeatRule.WEEKLY,
    )

    @Test
    fun `new event dialog is prefilled with the selected day and a free slot`() = runComposeUiTest {
        setContent {
            DesktopTheme {
                EventEditorDialog(
                    base = null,
                    date = day,
                    newId = "new",
                    zone = zone,
                    onDismiss = {},
                    onSave = {},
                )
            }
        }

        onNodeWithText("Новое событие").assertExists()
        onNodeWithTag("event-title").assertExists()
        onNodeWithText("12.11.2026").assertExists()
        onNodeWithText("09:00").assertExists()
        onNodeWithText("10:00").assertExists()
        onNodeWithTag("event-delete").assertDoesNotExist()
    }

    @Test
    fun `editing prefills fields of the event`() = runComposeUiTest {
        setContent {
            DesktopTheme {
                EventEditorDialog(
                    base = existingEvent(),
                    date = day,
                    newId = "new",
                    zone = zone,
                    onDismiss = {},
                    onSave = {},
                )
            }
        }

        onNodeWithText("Изменить событие").assertExists()
        onNodeWithText("Планёрка").assertExists()
        onNodeWithText("09:00").assertExists()
        onNodeWithText("10:00").assertExists()
        onNodeWithTag("event-delete").assertExists()
    }

    @Test
    fun `save reports the event built from the fields`() = runComposeUiTest {
        var saved: Event? = null
        setContent {
            DesktopTheme {
                EventEditorDialog(
                    base = null,
                    date = day,
                    newId = "new",
                    zone = zone,
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }

        onNodeWithTag("event-title").performTextInput("Созвон")
        onNodeWithTag("event-start").performTextClearance()
        onNodeWithTag("event-start").performTextInput("18:30")
        onNodeWithTag("event-end").performTextClearance()
        onNodeWithTag("event-end").performTextInput("19:15")
        onNodeWithTag("event-save").performClick()

        val event = requireNotNull(saved)
        assertEquals("Созвон", event.title)
        assertEquals("new", event.id)
        assertEquals(millis(18, 30), event.startTime)
        assertEquals(millis(19, 15), event.endTime)
        assertNull(event.repeatRule)
    }

    @Test
    fun `blank title shows an error and does not save`() = runComposeUiTest {
        var saved: Event? = null
        setContent {
            DesktopTheme {
                EventEditorDialog(
                    base = null,
                    date = day,
                    newId = "new",
                    zone = zone,
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }

        onNodeWithTag("event-save").performClick()

        onNodeWithText("Введите название").assertExists()
        assertNull(saved)
    }

    @Test
    fun `bad date shows an error and does not save`() = runComposeUiTest {
        var saved: Event? = null
        setContent {
            DesktopTheme {
                EventEditorDialog(
                    base = null,
                    date = day,
                    newId = "new",
                    zone = zone,
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }

        onNodeWithTag("event-title").performTextInput("Созвон")
        onNodeWithTag("event-date").performTextClearance()
        onNodeWithTag("event-date").performTextInput("2026-11-12")
        onNodeWithTag("event-save").performClick()

        onNodeWithText("Дата в формате дд.мм.гггг").assertExists()
        assertNull(saved)
    }

    @Test
    fun `end not after start is reported under the end field`() = runComposeUiTest {
        var saved: Event? = null
        setContent {
            DesktopTheme {
                EventEditorDialog(
                    base = null,
                    date = day,
                    newId = "new",
                    zone = zone,
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }

        onNodeWithTag("event-title").performTextInput("Созвон")
        onNodeWithTag("event-end").performTextClearance()
        onNodeWithTag("event-end").performTextInput("09:00")
        onNodeWithTag("event-save").performClick()

        onNodeWithText("Конец должен быть позже начала").assertExists()
        assertNull(saved)
    }

    @Test
    fun `repeat chip selects the rule`() = runComposeUiTest {
        var saved: Event? = null
        setContent {
            DesktopTheme {
                EventEditorDialog(
                    base = null,
                    date = day,
                    newId = "new",
                    zone = zone,
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }

        onNodeWithTag("event-title").performTextInput("Созвон")
        onNodeWithTag("event-repeat-weekly").performClick()
        onNodeWithTag("event-save").performClick()

        assertEquals(RepeatRule.WEEKLY, saved?.repeatRule)
    }

    @Test
    fun `saving an edited event keeps its id`() = runComposeUiTest {
        var saved: Event? = null
        setContent {
            DesktopTheme {
                EventEditorDialog(
                    base = existingEvent(),
                    date = day,
                    newId = "new",
                    zone = zone,
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }

        onNodeWithTag("event-title").performTextClearance()
        onNodeWithTag("event-title").performTextInput("Новая планёрка")
        onNodeWithTag("event-save").performClick()

        assertEquals("e1", saved?.id)
        assertEquals("Новая планёрка", saved?.title)
    }

    @Test
    fun `cancel dismisses without saving`() = runComposeUiTest {
        var dismissed = false
        var saved: Event? = null
        setContent {
            DesktopTheme {
                EventEditorDialog(
                    base = null,
                    date = day,
                    newId = "new",
                    zone = zone,
                    onDismiss = { dismissed = true },
                    onSave = { saved = it },
                )
            }
        }

        onNodeWithTag("event-cancel").performClick()

        assertEquals(true, dismissed)
        assertNull(saved)
    }

    @Test
    fun `delete asks for confirmation before removing the event`() = runComposeUiTest {
        var deleted: Event? = null
        setContent {
            DesktopTheme {
                EventEditorDialog(
                    base = existingEvent(),
                    date = day,
                    newId = "new",
                    zone = zone,
                    onDismiss = {},
                    onSave = {},
                    onDelete = { deleted = it },
                )
            }
        }

        onNodeWithTag("event-delete").performClick()
        onNodeWithText("Удалить событие?").assertExists()
        assertNull(deleted)

        onNodeWithTag("delete-confirm-yes").performClick()

        assertEquals("e1", deleted?.id)
    }

    @Test
    fun `delete confirmation can be cancelled`() = runComposeUiTest {
        var deleted: Event? = null
        setContent {
            DesktopTheme {
                EventEditorDialog(
                    base = existingEvent(),
                    date = day,
                    newId = "new",
                    zone = zone,
                    onDismiss = {},
                    onSave = {},
                    onDelete = { deleted = it },
                )
            }
        }

        onNodeWithTag("event-delete").performClick()
        onNodeWithTag("delete-confirm-no").performClick()

        onNodeWithTag("event-editor").assertExists()
        assertNull(deleted)
    }
}
