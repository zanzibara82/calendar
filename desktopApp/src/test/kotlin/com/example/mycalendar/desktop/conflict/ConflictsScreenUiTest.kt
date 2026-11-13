package com.example.mycalendar.desktop.conflict

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.example.mycalendar.desktop.theme.DesktopTheme
import com.example.mycalendar.desktop.theme.ThemeMode
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.SyncSnapshot
import com.example.mycalendar.domain.usecase.DeletionChoice
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Экран разбора конфликтов: очередь, спорные поля и исходы удаления. Экран получает готовое
 * состояние контроллера и только сообщает о выборе — записи здесь нет.
 */
@OptIn(ExperimentalTestApi::class)
class ConflictsScreenUiTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val base = SyncSnapshot(
        title = "Общий предок",
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(10),
        description = "",
        repeatRule = null,
    )
    private val cloud = base.copy(title = "Правка облака")

    private fun local() = Event(
        id = "e1",
        title = "Правка пользователя",
        startTime = 1_000L,
        endTime = 2_000L,
        reminderMinutes = listOf(10),
        calendarProvider = CalendarProvider.YANDEX,
        remoteId = "uid-1",
    )

    private fun conflict(
        id: String = "c1",
        kind: ConflictKind = ConflictKind.CONTENT,
        contested: Set<SyncField> = setOf(SyncField.TITLE),
        eventId: String = "e1",
        detectedAt: Long = 5_000L,
        remote: SyncSnapshot = cloud,
    ) = SyncConflict(
        id = id,
        eventId = eventId,
        provider = CalendarProvider.YANDEX,
        remoteId = "uid-1",
        kind = kind,
        base = base,
        remote = remote,
        contested = contested,
        detectedAt = detectedAt,
    )

    @Test
    fun `empty queue explains there is nothing to resolve`() = runComposeUiTest {
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                ConflictsScreen(
                    conflicts = emptyList(),
                    state = ConflictsController.State(),
                    zone = zone,
                    onBack = {},
                    onOpen = {},
                    onChoose = { _, _ -> },
                    onApply = {},
                    onResolveDeletion = {},
                    onDismissNotice = {},
                )
            }
        }

        onNodeWithText("Открытых конфликтов нет").assertExists()
    }

    @Test
    fun `queue lists conflicts and opens the chosen one`() = runComposeUiTest {
        var opened: String? = null
        val first = conflict(id = "c1")
        val second = conflict(
            id = "c2",
            kind = ConflictKind.REMOTE_DELETED,
            contested = emptySet(),
            eventId = "e2",
            remote = cloud.copy(title = "Удалённая в облаке"),
        )
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                ConflictsScreen(
                    conflicts = listOf(first, second),
                    state = ConflictsController.State(),
                    zone = zone,
                    onBack = {},
                    onOpen = { opened = it },
                    onChoose = { _, _ -> },
                    onApply = {},
                    onResolveDeletion = {},
                    onDismissNotice = {},
                )
            }
        }

        onNodeWithText("Правка облака").assertExists()
        onNodeWithText("Удалённая в облаке").assertExists()
        onNodeWithText("1 спорное поле").assertExists()
        onNodeWithText("Событие удалено в облаке, локально есть ваши правки").assertExists()

        onNodeWithTag("conflict-c1").performClick()
        assertEquals("c1", opened)
    }

    @Test
    fun `content conflict shows both values and reports the field choice`() = runComposeUiTest {
        var chosen: Pair<SyncField, Boolean>? = null
        var applied = false
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                ConflictsScreen(
                    conflicts = listOf(conflict()),
                    state = ConflictsController.State(
                        selected = conflict(),
                        local = local(),
                    ),
                    zone = zone,
                    onBack = {},
                    onOpen = {},
                    onChoose = { field, fromCloud -> chosen = field to fromCloud },
                    onApply = { applied = true },
                    onResolveDeletion = {},
                    onDismissNotice = {},
                )
            }
        }

        onNodeWithText("Название").assertExists()
        onNodeWithText("Локальное: Правка пользователя").assertExists()
        onNodeWithText("Из облака: Правка облака").assertExists()

        onNodeWithTag("conflict-field-title-cloud", useUnmergedTree = true).performClick()
        assertEquals(SyncField.TITLE to true, chosen)

        onNodeWithTag("conflict-apply", useUnmergedTree = true).performClick()
        assertTrue(applied)
    }

    @Test
    fun `saving disables the apply button`() = runComposeUiTest {
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                ConflictsScreen(
                    conflicts = listOf(conflict()),
                    state = ConflictsController.State(
                        selected = conflict(),
                        local = local(),
                        saving = true,
                    ),
                    zone = zone,
                    onBack = {},
                    onOpen = {},
                    onChoose = { _, _ -> },
                    onApply = {},
                    onResolveDeletion = {},
                    onDismissNotice = {},
                )
            }
        }

        onNodeWithTag("conflict-apply", useUnmergedTree = true).assertIsNotEnabled()
    }

    @Test
    fun `deleting everywhere asks for confirmation first`() = runComposeUiTest {
        var decision: DeletionChoice? = null
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                ConflictsScreen(
                    conflicts = listOf(conflict(kind = ConflictKind.LOCAL_DELETED, contested = setOf(SyncField.TITLE))),
                    state = ConflictsController.State(
                        selected = conflict(kind = ConflictKind.LOCAL_DELETED, contested = setOf(SyncField.TITLE)),
                        local = local(),
                    ),
                    zone = zone,
                    onBack = {},
                    onOpen = {},
                    onChoose = { _, _ -> },
                    onApply = {},
                    onResolveDeletion = { decision = it },
                    onDismissNotice = {},
                )
            }
        }

        onNodeWithText("Удалить везде").assertExists()
        onNodeWithTag("conflict-delete-everywhere", useUnmergedTree = true).performClick()
        // Исход необратим: до подтверждения ничего не происходит.
        assertEquals(null, decision)
        onNodeWithText("Удалить событие?").assertExists()

        onNodeWithText("Удалить", useUnmergedTree = true).performClick()
        assertEquals(DeletionChoice.DELETE_EVERYWHERE, decision)
    }

    @Test
    fun `restoring after a cloud deletion recreates the event in the cloud`() = runComposeUiTest {
        var decision: DeletionChoice? = null
        val deletion = conflict(kind = ConflictKind.REMOTE_DELETED, contested = emptySet())
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                ConflictsScreen(
                    conflicts = listOf(deletion),
                    state = ConflictsController.State(selected = deletion, local = local()),
                    zone = zone,
                    onBack = {},
                    onOpen = {},
                    onChoose = { _, _ -> },
                    onApply = {},
                    onResolveDeletion = { decision = it },
                    onDismissNotice = {},
                )
            }
        }

        onNodeWithTag("conflict-restore", useUnmergedTree = true).performClick()
        assertEquals(DeletionChoice.RESTORE_FROM_CLOUD, decision)
    }

    @Test
    fun `notice explains why the choice was not applied`() = runComposeUiTest {
        var dismissed = false
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                ConflictsScreen(
                    conflicts = listOf(conflict()),
                    state = ConflictsController.State(notice = ConflictsController.Notice.STALE),
                    zone = zone,
                    onBack = {},
                    onOpen = {},
                    onChoose = { _, _ -> },
                    onApply = {},
                    onResolveDeletion = {},
                    onDismissNotice = { dismissed = true },
                )
            }
        }

        onNodeWithText("Конфликт изменился — откройте его заново из очереди").assertExists()
        onNodeWithTag("conflict-notice-dismiss", useUnmergedTree = true).performClick()
        assertTrue(dismissed)
    }
}
