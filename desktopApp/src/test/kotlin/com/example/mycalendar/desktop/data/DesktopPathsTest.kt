package com.example.mycalendar.desktop.data

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class DesktopPathsTest {

    @Test
    fun `events file lives under APPDATA`() {
        val appData = System.getenv("APPDATA")
        assumeTrue("APPDATA не задан — проверка имеет смысл только на Windows", appData != null)

        assertEquals(Path.of(appData, "MyCalendar", "events.json"), DesktopPaths.eventsFile())
    }
}
