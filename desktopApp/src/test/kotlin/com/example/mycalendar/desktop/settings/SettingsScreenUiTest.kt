package com.example.mycalendar.desktop.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.example.mycalendar.desktop.theme.DesktopTheme
import com.example.mycalendar.desktop.theme.ThemeMode
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.UserAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SettingsScreenUiTest {

    private val defaults = DesktopSettings()

    @Test
    fun `shows sections and current values`() = runComposeUiTest {
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = defaults,
                    dataDir = "C:\\Users\\me\\AppData\\Roaming\\MyCalendar",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = {},
                    onQuietChange = {},
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                )
            }
        }

        onNodeWithText("Оформление").assertExists()
        onNodeWithText("Напоминания").assertExists()
        onNodeWithText("Окно").assertExists()
        onNodeWithText("О приложении").assertExists()
        onNodeWithText("Системная").assertExists()
        onNodeWithText("Светлая").assertExists()
        onNodeWithText("Тёмная").assertExists()
        onNodeWithText("MyCalendar 0.1.0").assertExists()
        // Оба сигнала по умолчанию — системные.
        assertEquals(2, onAllNodesWithText("Системный").fetchSemanticsNodes().size)
    }

    @Test
    fun `picking a theme reports it`() = runComposeUiTest {
        var mode by mutableStateOf(ThemeMode.SYSTEM)
        setContent {
            DesktopTheme(mode) {
                SettingsScreen(
                    settings = DesktopSettings(themeMode = mode),
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = { mode = it },
                    onQuietChange = {},
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                )
            }
        }

        onNodeWithTag("settings-theme-light", useUnmergedTree = true).performClick()

        assertEquals(ThemeMode.LIGHT, mode)
        onNodeWithTag("settings-theme-dark", useUnmergedTree = true).performClick()
        assertEquals(ThemeMode.DARK, mode)
    }

    @Test
    fun `quiet switch reports toggle`() = runComposeUiTest {
        var quiet by mutableStateOf(false)
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = DesktopSettings(quietMode = quiet),
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = {},
                    onQuietChange = { quiet = it },
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                )
            }
        }

        onNodeWithTag("settings-quiet", useUnmergedTree = true).performClick()

        assertTrue(quiet)
    }

    @Test
    fun `tray checkbox reports toggle`() = runComposeUiTest {
        var tray by mutableStateOf(false)
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = DesktopSettings(minimizeToTray = tray),
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = {},
                    onQuietChange = {},
                    onMinimizeToTrayChange = { tray = it },
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                )
            }
        }

        onNodeWithTag("settings-minimize-to-tray").performClick()

        assertTrue(tray)
    }

    @Test
    fun `autostart checkbox reports toggle`() = runComposeUiTest {
        var autostart by mutableStateOf(false)
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = defaults,
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = {},
                    onQuietChange = {},
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                    autostartSupported = true,
                    autostartEnabled = autostart,
                    onAutostartChange = { autostart = it },
                )
            }
        }

        onNodeWithTag("settings-autostart").performScrollTo().performClick()

        assertTrue(autostart)
    }

    @Test
    fun `autostart is disabled without packaged build`() = runComposeUiTest {
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = defaults,
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = {},
                    onQuietChange = {},
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                )
            }
        }

        onNodeWithTag("settings-autostart").assertIsNotEnabled()
        onNodeWithText("Доступно в установленной версии").assertExists()
    }

    @Test
    fun `back button reports navigation`() = runComposeUiTest {
        var backed = false
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = defaults,
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = { backed = true },
                    onThemeChange = {},
                    onQuietChange = {},
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                )
            }
        }

        onNodeWithTag("settings-back").performClick()

        assertTrue(backed)
    }

    @Test
    fun `chosen sound shows file name and can be reset`() = runComposeUiTest {
        var alarm by mutableStateOf<String?>("file:///C:/sounds/alarm.ogg")
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = DesktopSettings(alarmSoundUri = alarm),
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = {},
                    onQuietChange = {},
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = { alarm = null },
                    onPickBirthday = {},
                    onResetBirthday = {},
                )
            }
        }

        onNodeWithTag("settings-sound-alarm").assertExists()
        onNodeWithText("alarm.ogg").assertExists()

        onNodeWithTag("settings-reset-alarm").performClick()

        assertEquals(null, alarm)
        onNodeWithTag("settings-sound-alarm").assertExists()
    }

    @Test
    fun `sound label maps uri to file name`() {
        assertEquals("Системный", soundLabel(null))
        assertEquals("alarm.ogg", soundLabel("file:///C:/sounds/alarm.ogg"))
        assertEquals("rain.ogg", soundLabel("file:///home/me/%D0%B7%D0%B2%D1%83%D0%BA/rain.ogg"))
    }

    @Test
    fun `connected account shows who is signed in and can sign out`() = runComposeUiTest {
        var signedOut = false
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = defaults,
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = {},
                    onQuietChange = {},
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                    account = UserAccount(
                        provider = CalendarProvider.YANDEX,
                        email = "ivanov@yandex.ru",
                        displayName = "Иван Иванов",
                    ),
                    onSignOut = { signedOut = true },
                )
            }
        }

        onNodeWithText("Подключено: Иван Иванов").assertExists()
        onNodeWithTag("settings-sign-out").performClick()

        assertTrue(signedOut)
    }

    @Test
    fun `signed out card invites to sign in`() = runComposeUiTest {
        var signedIn = false
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = defaults,
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = {},
                    onQuietChange = {},
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                    account = null,
                    onSignIn = { signedIn = true },
                )
            }
        }

        onNodeWithText("Не подключён", substring = true).assertExists()
        onNodeWithTag("settings-sign-in").performClick()

        assertTrue(signedIn)
    }

    @Test
    fun `sign in error is shown in the card`() = runComposeUiTest {
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = defaults,
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = {},
                    onQuietChange = {},
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                    loginError = "Нет сети",
                )
            }
        }

        onNodeWithTag("settings-login-error").assertExists()
        onNodeWithText("Нет сети").assertExists()
    }

    @Test
    fun `sync button needs a connected account`() = runComposeUiTest {
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = defaults,
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = {},
                    onQuietChange = {},
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                    account = null,
                    syncStatus = "Синхронизация ещё не выполнялась",
                )
            }
        }

        onNodeWithTag("settings-sync").assertIsNotEnabled()
        onNodeWithText("Синхронизация ещё не выполнялась").assertExists()
    }

    @Test
    fun `sync button reports click with a connected account`() = runComposeUiTest {
        var synced = false
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = defaults,
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = {},
                    onQuietChange = {},
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                    account = UserAccount(CalendarProvider.YANDEX, "ivanov@yandex.ru"),
                    syncStatus = "Готово: отправлено 2, получено 5, конфликтов 1",
                    onSync = { synced = true },
                )
            }
        }

        onNodeWithText("Готово: отправлено 2, получено 5, конфликтов 1").assertExists()
        onNodeWithTag("settings-sync").performClick()

        assertTrue(synced)
    }

    @Test
    fun `no conflicts means no conflicts row`() = runComposeUiTest {
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = defaults,
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = {},
                    onQuietChange = {},
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                    account = UserAccount(CalendarProvider.YANDEX, "ivanov@yandex.ru"),
                    syncStatus = "Готово",
                    conflictCount = 0,
                )
            }
        }

        onNodeWithTag("settings-conflicts").assertDoesNotExist()
        onNodeWithTag("settings-open-conflicts").assertDoesNotExist()
    }

    @Test
    fun `open conflicts row counts them and opens the screen`() = runComposeUiTest {
        var opened = false
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = defaults,
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = {},
                    onQuietChange = {},
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                    conflictCount = 2,
                    onOpenConflicts = { opened = true },
                )
            }
        }

        onNodeWithText("Конфликты: 2").assertExists()
        onNodeWithTag("settings-open-conflicts").performClick()

        assertTrue(opened)
    }

    @Test
    fun `running sync disables the button and says so`() = runComposeUiTest {
        setContent {
            DesktopTheme(ThemeMode.DARK) {
                SettingsScreen(
                    settings = defaults,
                    dataDir = "dir",
                    version = "0.1.0",
                    onBack = {},
                    onThemeChange = {},
                    onQuietChange = {},
                    onMinimizeToTrayChange = {},
                    onPickAlarm = {},
                    onResetAlarm = {},
                    onPickBirthday = {},
                    onResetBirthday = {},
                    account = UserAccount(CalendarProvider.YANDEX, "ivanov@yandex.ru"),
                    syncStatus = "Синхронизация…",
                    syncing = true,
                )
            }
        }

        onNodeWithTag("settings-sync").assertIsNotEnabled()
        onNodeWithText("Синхронизация…").assertExists()
    }
}
