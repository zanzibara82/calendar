package com.example.mycalendar.desktop.data

import com.example.mycalendar.domain.model.SyncConflict
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
private data class ConflictsFile(
    val version: Int = 1,
    val conflicts: List<SyncConflict> = emptyList(),
)

/**
 * Очередь конфликтов desktop-клиента в одном JSON-файле.
 *
 * Замена атомарная, как у событий и настроек: падение посередине записи не должно
 * оставить полузаписанной очереди и потерять уже обнаруженные конфликты. Неразборчивый
 * файл откладывается с суффиксом `.corrupt-<время>`, работа продолжается с пустой очередью.
 */
class DesktopConflictStore(
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
    private val loaded = MutableStateFlow<List<SyncConflict>?>(null)
    val conflicts: StateFlow<List<SyncConflict>?> = loaded.asStateFlow()

    suspend fun snapshot(): List<SyncConflict> {
        ensureLoaded()
        return loaded.value.orEmpty()
    }

    suspend fun update(transform: (List<SyncConflict>) -> List<SyncConflict>) {
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

    private suspend fun read(): List<SyncConflict> = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext emptyList()
        try {
            json.decodeFromString<ConflictsFile>(Files.readString(file, StandardCharsets.UTF_8)).conflicts
        } catch (e: SerializationException) {
            quarantine(e)
        }
    }

    private fun quarantine(cause: Exception): List<SyncConflict> {
        val backup = file.resolveSibling("${file.fileName}.corrupt-${clock()}")
        val moved = runCatching {
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING)
        }
        System.err.println(
            "conflicts file ${file.toAbsolutePath()} unreadable (${cause.message}); " +
                if (moved.isSuccess) "moved to $backup" else "kept in place",
        )
        return emptyList()
    }

    private suspend fun write(conflicts: List<SyncConflict>) = withContext(Dispatchers.IO) {
        val parent = file.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "conflicts-", ".tmp")
        try {
            Files.writeString(
                temp,
                json.encodeToString(ConflictsFile(conflicts = conflicts)),
                StandardCharsets.UTF_8,
            )
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
