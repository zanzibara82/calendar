package com.example.mycalendar.desktop.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Автозапуск проверяется на подставном исполнителе `reg`: настоящий реестр
 * тесты не трогают.
 */
class WindowsAutostartStoreTest {

    private val exe = "C:\\Program Files\\MyCalendar\\MyCalendar.exe"
    private val calls = mutableListOf<List<String>>()

    @Test
    fun `without packaged executable autostart is unsupported and writes nothing`() {
        val store = WindowsAutostartStore(executablePath = null, exec = { calls += it; 0 })

        assertFalse(store.supported)
        assertFalse(store.isEnabled())
        assertFalse(store.setEnabled(true))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `reads enabled state from registry exit code`() {
        assertEquals(true, store(exitCode = 0).isEnabled())
        assertEquals(false, store(exitCode = 1).isEnabled())
    }

    @Test
    fun `enabling writes quoted executable to the per-user run key`() {
        val store = store(exitCode = 0)

        assertTrue(store.setEnabled(true))

        assertEquals(
            listOf(
                "reg", "add",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "MyCalendar",
                "/t", "REG_SZ",
                "/d", "\"$exe\"",
                "/f",
            ),
            calls.single(),
        )
    }

    @Test
    fun `disabling deletes the run value`() {
        val store = store(exitCode = 0)

        assertTrue(store.setEnabled(false))

        assertEquals(
            listOf(
                "reg", "delete",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "MyCalendar",
                "/f",
            ),
            calls.single(),
        )
    }

    @Test
    fun `failed registry write reports false`() {
        val store = store(exitCode = 1)

        assertFalse(store.setEnabled(true))
        assertEquals(1, calls.size)
    }

    @Test
    fun `packaged executable is only the launcher`() {
        assertEquals(exe, packagedExecutablePath(exe))
        assertNull(packagedExecutablePath("C:\\Program Files\\Java\\bin\\java.exe"))
        assertNull(packagedExecutablePath(null))
    }

    private fun store(exitCode: Int) = WindowsAutostartStore(
        executablePath = exe,
        exec = { calls += it; exitCode },
    )
}
