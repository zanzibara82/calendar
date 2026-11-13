package com.example.mycalendar.desktop.data

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

/** Подключённая учётная запись провайдера без секретов: токен в файл не попадает. */
@Serializable
data class StoredAccount(
    val email: String,
    val displayName: String? = null,
)

@Serializable
private data class AccountFile(
    val version: Int = 1,
    /** Ключ — имя [com.example.mycalendar.domain.model.CalendarProvider]. */
    val accounts: Map<String, StoredAccount> = emptyMap(),
)

/**
 * Учётные записи desktop-клиента в account.json.
 *
 * Токен здесь сознательно не хранится: до OAuth-шлюза Windows он живёт только в памяти,
 * а защищённое хранение секретов появится вместе с входом. Запись атомарная, как у событий,
 * настроек и очереди конфликтов; неразборчивый файл откладывается с суффиксом `.corrupt-<время>`.
 */
class DesktopAuthStore(
    private val file: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val mutex = Mutex()

    /** null — файл ещё не прочитан; пустая карта — прочитан и пуст. */
    private val loaded = MutableStateFlow<Map<String, StoredAccount>?>(null)
    val accounts: StateFlow<Map<String, StoredAccount>?> = loaded.asStateFlow()

    suspend fun snapshot(): Map<String, StoredAccount> {
        ensureLoaded()
        return loaded.value.orEmpty()
    }

    suspend fun update(transform: (Map<String, StoredAccount>) -> Map<String, StoredAccount>) {
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

    private suspend fun read(): Map<String, StoredAccount> = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext emptyMap()
        try {
            json.decodeFromString<AccountFile>(Files.readString(file, StandardCharsets.UTF_8)).accounts
        } catch (e: SerializationException) {
            quarantine(e)
        }
    }

    private fun quarantine(cause: Exception): Map<String, StoredAccount> {
        val backup = file.resolveSibling("${file.fileName}.corrupt-${clock()}")
        val moved = runCatching {
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING)
        }
        System.err.println(
            "account file ${file.toAbsolutePath()} unreadable (${cause.message}); " +
                if (moved.isSuccess) "moved to $backup" else "kept in place",
        )
        return emptyMap()
    }

    private suspend fun write(accounts: Map<String, StoredAccount>) = withContext(Dispatchers.IO) {
        val parent = file.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "account-", ".tmp")
        try {
            Files.writeString(
                temp,
                json.encodeToString(AccountFile(accounts = accounts)),
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
