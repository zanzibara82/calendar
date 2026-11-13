package com.example.mycalendar.desktop.data

import com.example.mycalendar.desktop.settings.DesktopSettings
import com.example.mycalendar.desktop.theme.ThemeMode as DesktopThemeMode
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ThemeMode
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopSettingsRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newRepository(): Triple<DesktopSettingsRepository, Path, DesktopSettingsStore> {
        val file = folder.newFolder().toPath().resolve("settings.json")
        val store = DesktopSettingsStore(file)
        return Triple(DesktopSettingsRepository(store), file, store)
    }

    @Test
    fun `missing file gives defaults`() = runBlocking {
        val (repository, _, _) = newRepository()

        assertEquals(ThemeMode.SYSTEM, repository.themeMode.first())
        assertEquals(0L, repository.lastSyncTime.first())
        assertEquals(0L, repository.lastSyncTime(CalendarProvider.YANDEX).first())
        assertNull(repository.collectionUrl(CalendarProvider.YANDEX).first())
        assertNull(repository.calendarName(CalendarProvider.YANDEX).first())
    }

    @Test
    fun `theme is mapped to the stored desktop enum`() = runBlocking {
        val (repository, _, store) = newRepository()

        repository.setThemeMode(ThemeMode.DARK)

        assertEquals(DesktopThemeMode.DARK, store.load().themeMode)
        assertEquals(ThemeMode.DARK, repository.themeMode.first())
    }

    @Test
    fun `provider metadata survives reload and updates the aggregate`() = runBlocking {
        val (repository, file, _) = newRepository()

        repository.setLastSyncTime(CalendarProvider.YANDEX, 1_700_000_000_000)
        repository.setCollectionUrl(CalendarProvider.YANDEX, "https://caldav.yandex.ru/calendars/user/events/")
        repository.setCalendarName(CalendarProvider.YANDEX, "Личный")

        val reloaded = DesktopSettingsRepository(DesktopSettingsStore(file))

        assertEquals(1_700_000_000_000, reloaded.lastSyncTime(CalendarProvider.YANDEX).first())
        assertEquals(1_700_000_000_000, reloaded.lastSyncTime.first())
        assertEquals(
            "https://caldav.yandex.ru/calendars/user/events/",
            reloaded.collectionUrl(CalendarProvider.YANDEX).first(),
        )
        assertEquals("Личный", reloaded.calendarName(CalendarProvider.YANDEX).first())
        assertEquals(0L, reloaded.lastSyncTime(CalendarProvider.GOOGLE).first())
    }

    @Test
    fun `clearing a collection forgets the url and name but keeps the time`() = runBlocking {
        val (repository, _, _) = newRepository()
        repository.setLastSyncTime(CalendarProvider.YANDEX, 42L)
        repository.setCollectionUrl(CalendarProvider.YANDEX, "https://caldav.yandex.ru/a/")
        repository.setCalendarName(CalendarProvider.YANDEX, "Рабочий")

        repository.clearCollectionUrl(CalendarProvider.YANDEX)

        assertEquals(42L, repository.lastSyncTime(CalendarProvider.YANDEX).first())
        assertNull(repository.collectionUrl(CalendarProvider.YANDEX).first())
        assertNull(repository.calendarName(CalendarProvider.YANDEX).first())
    }

    @Test
    fun `sound and quiet mode live in the same file`() = runBlocking {
        val (repository, _, store) = newRepository()

        repository.setQuietMode(true)
        repository.setAlarmSoundUri("file:///sounds/alarm.ogg")
        repository.setBirthdaySoundUri("file:///sounds/birthday.ogg")

        val saved = store.load()
        assertEquals(
            DesktopSettings().copy(
                quietMode = true,
                alarmSoundUri = "file:///sounds/alarm.ogg",
                birthdaySoundUri = "file:///sounds/birthday.ogg",
            ),
            saved,
        )
        assertEquals("file:///sounds/alarm.ogg", repository.alarmSoundUri.first())
        assertEquals("file:///sounds/birthday.ogg", repository.birthdaySoundUri.first())
        assertTrue(repository.quietMode.first())
    }
}
