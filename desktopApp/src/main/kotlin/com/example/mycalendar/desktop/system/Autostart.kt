package com.example.mycalendar.desktop.system

import java.io.File
import java.util.concurrent.TimeUnit

/** Автозапуск клиента при входе в систему. */
interface AutostartStore {

    /** Запись возможна только из установленной сборки. */
    val supported: Boolean

    fun isEnabled(): Boolean

    /** Возвращает true, только если реестр подтвердил новое состояние. */
    fun setEnabled(enabled: Boolean): Boolean
}

/**
 * Автозапуск через значение в `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`:
 * пишет `reg.exe`, поэтому нативных зависимостей нет. Путь к exe заключается
 * в кавычки — установка в Program Files содержит пробелы.
 */
class WindowsAutostartStore(
    private val executablePath: String? = packagedExecutablePath(),
    private val exec: (List<String>) -> Int = ::runReg,
) : AutostartStore {

    override val supported: Boolean get() = executablePath != null

    override fun isEnabled(): Boolean = supported && exec(queryCommand()) == 0

    override fun setEnabled(enabled: Boolean): Boolean {
        val path = executablePath ?: return false
        val command = if (enabled) addCommand(path) else deleteCommand()
        return exec(command) == 0
    }
}

/** Путь к exe установленной сборки; при запуске из `java.exe` — null. */
fun packagedExecutablePath(
    command: String? = ProcessHandle.current().info().command().orElse(null),
): String? = command?.takeIf { File(it).name.equals(LAUNCHER_NAME, ignoreCase = true) }

private fun queryCommand() = listOf("reg", "query", RUN_KEY, "/v", VALUE_NAME)

private fun addCommand(path: String) = listOf(
    "reg", "add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ", "/d", "\"$path\"", "/f",
)

private fun deleteCommand() = listOf("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f")

private fun runReg(args: List<String>): Int = try {
    val process = ProcessBuilder(args)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    if (process.waitFor(REG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.exitValue()
    } else {
        process.destroyForcibly()
        -1
    }
} catch (error: java.io.IOException) {
    -1
}

private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
private const val VALUE_NAME = "MyCalendar"
private const val LAUNCHER_NAME = "MyCalendar.exe"
private const val REG_TIMEOUT_SECONDS = 5L
