package com.example.mycalendar.desktop.reminder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.example.mycalendar.desktop.theme.DesktopTheme
import com.example.mycalendar.desktop.theme.ThemeMode
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ReminderBannerUiTest {

    private val label = "Начало в 09:30 (через 5 мин)"

    @Test
    fun `shows event title and label`() = runComposeUiTest {
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                ReminderBanner(
                    title = "Планёрка",
                    label = label,
                    onOpen = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithTag("reminder-banner").assertExists()
        onNodeWithText("Планёрка").assertExists()
        onNodeWithText(label).assertExists()
        onNodeWithText("Открыть").assertExists()
        onNodeWithText("Закрыть").assertExists()
    }

    @Test
    fun `open button reports`() = runComposeUiTest {
        var opened = false
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                ReminderBanner(
                    title = "Планёрка",
                    label = label,
                    onOpen = { opened = true },
                    onDismiss = {},
                )
            }
        }

        onNodeWithTag("reminder-open").performClick()

        assertTrue(opened)
    }

    @Test
    fun `dismiss button reports`() = runComposeUiTest {
        var dismissed = false
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                ReminderBanner(
                    title = "Планёрка",
                    label = label,
                    onOpen = {},
                    onDismiss = { dismissed = true },
                )
            }
        }

        onNodeWithTag("reminder-dismiss").performClick()

        assertTrue(dismissed)
    }
}
