package com.example.mycalendar.desktop.data

import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.SyncSnapshot
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

/**
 * Строка файла: событие плюс служебное состояние, которого нет в доменной модели.
 * Тот же набор, что у Room-сущности Android: tombstone, флаг отправки, снимок-предок.
 */
@Serializable
data class StoredEvent(
    val event: Event,
    val isDeleted: Boolean = false,
    val pendingSync: Boolean = true,
    val syncBase: SyncSnapshot? = null,
)

@Serializable
private data class EventsFile(
    val version: Int = 1,
    val events: List<StoredEvent> = emptyList(),
)

/**
 * События desktop-клиента в одном JSON-файле.
 *
 * Файл перезаписывается целиком при каждом изменении: календарь содержит тысячи событий,
 * а не миллионы, зато атомарная замена (временный файл в том же каталоге + move) не оставляет
 * полузаписанного файла при падении. Повреждённый файл не затирается: он откладывается
 * рядом с суффиксом `.corrupt-<время>`, работа продолжается с пустым списком.
 */
class EventFileStore(
    private val file: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val mutex = Mutex()

    /** null — файл ещё не прочитан; пустой список — прочитан и пуст. */
    private val loaded = MutableStateFlow<List<StoredEvent>?>(null)
    val records: StateFlow<List<StoredEvent>?> = loaded.asStateFlow()

    suspend fun snapshot(): List<StoredEvent> {
        ensureLoaded()
        return loaded.value.orEmpty()
    }

    suspend fun update(transform: (List<StoredEvent>) -> List<StoredEvent>) {
        mutex.withLock {
            val current = loaded.value ?: read().also { loaded.value = it }
            val next = transform(current)
            write(next)
            loaded.value = next
        }
    }

    private suspend fun ensureLoaded() {
        mutex.withLock {
            if (loaded.value == null) loaded.value = read()
        }
    }

    private suspend fun read(): List<StoredEvent> = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext emptyList()
        try {
            json.decodeFromString<EventsFile>(Files.readString(file, StandardCharsets.UTF_8)).events
        } catch (e: SerializationException) {
            // Ошибка ввода-вывода не повод считать данные потерянными: её пробрасываем,
            // а в сторону откладываем только файл, который точно не разбирается.
            quarantine(e)
        }
    }

    private fun quarantine(cause: Exception): List<StoredEvent> {
        val backup = file.resolveSibling("${file.fileName}.corrupt-${clock()}")
        val moved = runCatching {
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING)
        }
        System.err.println(
            "events file ${file.toAbsolutePath()} unreadable (${cause.message}); " +
                if (moved.isSuccess) "moved to $backup" else "kept in place",
        )
        return emptyList()
    }

    private suspend fun write(records: List<StoredEvent>) = withContext(Dispatchers.IO) {
        val parent = file.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "events-", ".tmp")
        try {
            Files.writeString(temp, json.encodeToString(EventsFile(events = records)), StandardCharsets.UTF_8)
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
