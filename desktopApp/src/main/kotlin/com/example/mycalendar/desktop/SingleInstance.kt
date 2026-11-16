package com.example.mycalendar.desktop

import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Единственный экземпляр клиента на каталог данных.
 *
 * Повторный запуск exe не должен заводить вторую копию: два процесса показали бы две иконки
 * в трее и писали бы одни и те же events.json/settings.json. Право на запуск — блокировка
 * файла в каталоге данных. ОС снимает её при завершении процесса, поэтому упавший клиент
 * не оставляет «вечную» блокировку.
 *
 * Второй экземпляр выходит, оставив в [requestShow] метку-запрос; работающий экземпляр
 * замечает новую метку и показывает окно из трея.
 */
class SingleInstance(dataDir: Path) : Closeable {

    private val lockFile = dataDir.resolve("instance.lock")
    private val requestFile = dataDir.resolve("show.request")

    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /** @return true, если этот процесс стал единственным; false — уже работает другой. */
    fun tryAcquire(): Boolean {
        Files.createDirectories(lockFile.parent)
        val opened = FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        val acquired = try {
            opened.tryLock()
        } catch (e: OverlappingFileLockException) {
            // Блокировка уже взята внутри этого же JVM — для «второго запуска» это то же
            // самое, что чужая блокировка.
            null
        }
        if (acquired == null) {
            opened.close()
            return false
        }
        channel = opened
        lock = acquired
        return true
    }

    /** Просьба работающему экземпляру показать окно. */
    fun requestShow() {
        Files.createDirectories(requestFile.parent)
        val token = "${System.nanoTime()}-${UUID.randomUUID()}"
        // Через временный файл: читатель не должен увидеть недописанную метку.
        val temp = requestFile.resolveSibling("show.request.$token.tmp")
        Files.writeString(temp, token, StandardCharsets.UTF_8)
        Files.move(
            temp,
            requestFile,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    /** @return метка нового запроса, если она отличается от уже замеченной [lastSeen]. */
    fun pollShowRequest(lastSeen: String?): String? {
        val token = runCatching { Files.readString(requestFile, StandardCharsets.UTF_8) }
            .getOrNull()
            ?: return null
        return token.takeIf { it.isNotBlank() && it != lastSeen }
    }

    override fun close() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lock = null
        channel = null
    }
}
