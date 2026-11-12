package com.example.mycalendar.desktop.data

import com.example.mycalendar.desktop.settings.DesktopSettings
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SettingsFile(
    val version: Int = 1,
    val settings: DesktopSettings = DesktopSettings(),
)

/**
 * Настройки desktop-клиента в одном JSON-файле рядом с событиями.
 *
 * Как и файл событий, settings.json перезаписывается целиком через временный файл
 * и атомарный move: файл крошечный, зато падение посередине записи не оставит
 * полузаписанных настроек. Неразборчивый файл откладывается с суффиксом
 * `.corrupt-<время>`, работа продолжается с настройками по умолчанию.
 */
class DesktopSettingsStore(
    private val file: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val mutex = Mutex()

    /** null — файл ещё не прочитан. */
    private val loaded = MutableStateFlow<DesktopSettings?>(null)
    val settings: StateFlow<DesktopSettings?> = loaded.asStateFlow()

    suspend fun load(): DesktopSettings {
        mutex.withLock {
            if (loaded.value == null) loaded.value = read()
            return loaded.value!!
        }
    }

    suspend fun update(transform: (DesktopSettings) -> DesktopSettings) {
        mutex.withLock {
            val current = loaded.value ?: read().also { loaded.value = it }
            val next = transform(current)
            write(next)
            loaded.value = next
        }
    }

    private suspend fun read(): DesktopSettings = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext DesktopSettings()
        try {
            json.decodeFromString<SettingsFile>(Files.readString(file, StandardCharsets.UTF_8)).settings
        } catch (e: SerializationException) {
            quarantine(e)
        }
    }

    private fun quarantine(cause: Exception): DesktopSettings {
        val backup = file.resolveSibling("${file.fileName}.corrupt-${clock()}")
        val moved = runCatching {
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING)
        }
        System.err.println(
            "settings file ${file.toAbsolutePath()} unreadable (${cause.message}); " +
                if (moved.isSuccess) "moved to $backup" else "kept in place",
        )
        return DesktopSettings()
    }

    private suspend fun write(settings: DesktopSettings) = withContext(Dispatchers.IO) {
        val parent = file.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "settings-", ".tmp")
        try {
            Files.writeString(temp, json.encodeToString(SettingsFile(settings = settings)), StandardCharsets.UTF_8)
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
