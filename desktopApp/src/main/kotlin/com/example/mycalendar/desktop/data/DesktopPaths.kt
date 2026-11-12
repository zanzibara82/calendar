package com.example.mycalendar.desktop.data

import java.nio.file.Path

/**
 * Каталог данных desktop-клиента: %APPDATA%\MyCalendar на Windows,
 * ~/.mycalendar вне Windows (тесты и запуск из-под WSL).
 */
object DesktopPaths {

    fun dataDir(): Path {
        val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
        return if (appData != null) {
            Path.of(appData, "MyCalendar")
        } else {
            Path.of(System.getProperty("user.home"), ".mycalendar")
        }
    }

    fun eventsFile(): Path = dataDir().resolve("events.json")
}
