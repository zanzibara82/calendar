package com.example.mycalendar.desktop.data

import com.example.mycalendar.desktop.settings.DesktopSettings
import com.example.mycalendar.desktop.theme.ThemeMode
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopSettingsStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newStore(): Pair<DesktopSettingsStore, java.nio.file.Path> {
        val file = folder.newFolder().toPath().resolve("settings.json")
        return DesktopSettingsStore(file) to file
    }

    @Test
    fun `missing file gives defaults`() = runBlocking {
        val (store, file) = newStore()

        assertEquals(DesktopSettings(), store.load())
        assertFalse(Files.exists(file))
    }

    @Test
    fun `saved settings survive reload`() = runBlocking {
        val (store, file) = newStore()
        store.update {
            it.copy(themeMode = ThemeMode.LIGHT, quietMode = true, alarmSoundUri = "file:///sounds/alarm.ogg")
        }

        val reloaded = DesktopSettingsStore(file).load()

        assertEquals(ThemeMode.LIGHT, reloaded.themeMode)
        assertTrue(reloaded.quietMode)
        assertEquals("file:///sounds/alarm.ogg", reloaded.alarmSoundUri)
        assertNull(reloaded.birthdaySoundUri)
    }

    @Test
    fun `corrupt file is quarantined and defaults are used`() = runBlocking {
        val (store, file) = newStore()
        Files.writeString(file, "{ not json")

        val settings = store.load()

        assertEquals(DesktopSettings(), settings)
        assertFalse(Files.exists(file))
        val backups = Files.list(file.parent).use { paths ->
            paths.filter { it.fileName.toString().startsWith("settings.json.corrupt-") }.toList()
        }
        assertEquals(1, backups.size)
    }

    @Test
    fun `no temp files left after write`() = runBlocking {
        val (store, file) = newStore()
        store.update { it.copy(quietMode = true) }

        val leftovers = Files.list(file.parent).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".tmp") }.toList()
        }
        assertTrue(leftovers.isEmpty())
    }

    @Test
    fun `parallel updates are serialized`() = runBlocking {
        val (store, file) = newStore()
        (0 until 20).map { index ->
            async { store.update { it.copy(quietMode = index % 2 == 0) } }
        }.awaitAll()

        // Каждая запись целиком читает текущее состояние под mutex: последняя
        // применённая трансформация видна и в памяти, и в файле.
        assertEquals(DesktopSettingsStore(file).load(), store.load())
    }

}
