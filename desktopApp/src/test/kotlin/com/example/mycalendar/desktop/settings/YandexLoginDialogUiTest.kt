package com.example.mycalendar.desktop.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.example.mycalendar.desktop.auth.DeviceCode
import com.example.mycalendar.desktop.theme.DesktopTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class YandexLoginDialogUiTest {

    private val code = DeviceCode(
        userCode = "uo3ub7r5",
        verificationUrl = "https://ya.ru/device",
        expiresInSeconds = 300,
    )

    @Test
    fun `shows the user code and the login page`() = runComposeUiTest {
        var opened = false
        setContent {
            DesktopTheme {
                YandexLoginDialog(
                    code = code,
                    onCopy = {},
                    onOpenPage = { opened = true },
                    onCancel = {},
                )
            }
        }

        onNodeWithText("uo3ub7r5").assertExists()
        onNodeWithText("https://ya.ru/device").assertExists()
        onNodeWithTag("login-open-page").performClick()

        assertTrue(opened)
    }

    @Test
    fun `copy and cancel are reported`() = runComposeUiTest {
        var copied = 0
        var cancelled = false
        setContent {
            DesktopTheme {
                YandexLoginDialog(
                    code = code,
                    onCopy = { copied++ },
                    onOpenPage = {},
                    onCancel = { cancelled = true },
                )
            }
        }

        onNodeWithTag("login-copy").performClick()
        assertEquals(1, copied)
        onNodeWithTag("login-cancel").performClick()

        assertTrue(cancelled)
    }

    @Test
    fun `open failure is shown inside the dialog`() = runComposeUiTest {
        setContent {
            DesktopTheme {
                YandexLoginDialog(
                    code = code,
                    error = "Не удалось открыть браузер",
                    onCopy = {},
                    onOpenPage = {},
                    onCancel = {},
                )
            }
        }

        onNodeWithTag("login-error").assertExists()
        onNodeWithText("Не удалось открыть браузер").assertExists()
    }
}
