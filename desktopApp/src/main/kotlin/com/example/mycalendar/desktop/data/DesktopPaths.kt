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

    fun settingsFile(): Path = dataDir().resolve("settings.json")

    fun conflictsFile(): Path = dataDir().resolve("conflicts.json")

    fun accountFile(): Path = dataDir().resolve("account.json")

    fun tokenFile(): Path = dataDir().resolve("token.bin")
}
