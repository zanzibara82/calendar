package com.example.mycalendar.desktop.data

import com.sun.jna.platform.win32.Crypt32Util
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Шифрование байтов токена перед записью на диск. */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/** Защищённое хранение токена между запусками процесса. */
interface TokenVault {
    suspend fun load(): String?
    suspend fun save(token: String)
    suspend fun clear()
}

/**
 * Токен Яндекса между запусками в отдельном файле.
 *
 * На диске лежит только шифротекст [cipher]; открытый токен в файл не попадает.
 * Нечитаемый blob (чужой файл, другой пользователь Windows, сбой шифра) не роняет
 * запуск: файл удаляется, и клиент просто просит войти заново.
 */
class DesktopTokenVault(
    private val file: Path,
    private val cipher: TokenCipher,
) : TokenVault {

    override suspend fun load(): String? = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext null
        val token = try {
            val plain = cipher.decrypt(Files.readAllBytes(file))
            String(plain, StandardCharsets.UTF_8)
        } catch (error: Exception) {
            System.err.println("token file ${file.toAbsolutePath()} unreadable (${error.message})")
            null
        }
        if (token.isNullOrBlank()) {
            Files.deleteIfExists(file)
            null
        } else {
            token
        }
    }

    override suspend fun save(token: String) = withContext(Dispatchers.IO) {
        if (token.isBlank()) {
            clearBlocking()
            return@withContext
        }
        val parent = file.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "token-", ".tmp")
        try {
            Files.write(temp, cipher.encrypt(token.toByteArray(StandardCharsets.UTF_8)))
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) { clearBlocking() }

    private fun clearBlocking() {
        Files.deleteIfExists(file)
    }
}

/**
 * Windows DPAPI: ключ шифрования выводится из учётной записи Windows, поэтому
 * скопированный файл токена на другом компьютере или у другого пользователя не читается.
 */
class DpapiTokenCipher : TokenCipher {

    override fun encrypt(plain: ByteArray): ByteArray = Crypt32Util.cryptProtectData(plain)

    override fun decrypt(blob: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(blob)
}
