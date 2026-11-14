package com.example.mycalendar.desktop.data

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.UserAccount
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopAuthRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val account = UserAccount(
        provider = CalendarProvider.YANDEX,
        email = "user@yandex.ru",
        displayName = "Пользователь",
    )
    private val session = YandexSession(token = "token-1", account = account)

    private class TempPaths(
        val account: Path,
        val settings: Path,
        val token: Path,
    )

    private fun newFiles(): TempPaths {
        val dir = folder.newFolder().toPath()
        return TempPaths(
            account = dir.resolve("account.json"),
            settings = dir.resolve("settings.json"),
            token = dir.resolve("token.bin"),
        )
    }

    private fun vault(files: TempPaths) = DesktopTokenVault(files.token, ReversingCipher())

    private fun repository(
        files: TempPaths,
        authorize: suspend () -> YandexSession,
    ): Pair<DesktopAuthRepository, DesktopSettingsRepository> {
        val settingsRepository = DesktopSettingsRepository(DesktopSettingsStore(files.settings))
        val repository = DesktopAuthRepository(
            store = DesktopAuthStore(files.account),
            settingsRepository = settingsRepository,
            tokenVault = vault(files),
            authorize = authorize,
        )
        return repository to settingsRepository
    }

    @Test
    fun `sign in stores the account and hands out the token`() = runBlocking {
        val files = newFiles()
        val (repository, _) = repository(files) { session }

        val result = repository.signInWithYandex()

        assertEquals(account, result.getOrNull())
        assertEquals(account, repository.accountFor(CalendarProvider.YANDEX))
        assertEquals(listOf(CalendarProvider.YANDEX), repository.connectedProviders())
        assertEquals("token-1", repository.getAccessToken())
        assertEquals("token-1", repository.getAccessToken(CalendarProvider.YANDEX))
        assertEquals("token-1", vault(files).load())
        val connection = repository.connections.first().single()
        assertEquals(CalendarProvider.YANDEX, connection.provider)
        assertEquals(account, connection.account)
    }

    @Test
    fun `account and token survive reload`() = runBlocking {
        val files = newFiles()
        val (first, _) = repository(files) { session }
        first.signInWithYandex()

        val (reloaded, _) = repository(files) { error("вход не нужен") }
        reloaded.restoreToken()

        assertEquals(account, reloaded.accountFor(CalendarProvider.YANDEX))
        assertEquals("token-1", reloaded.getAccessToken())
    }

    @Test
    fun `token without an account is dropped on restore`() = runBlocking {
        val files = newFiles()
        vault(files).save("stray")
        val (repository, _) = repository(files) { session }

        repository.restoreToken()

        assertNull(repository.getAccessToken())
        assertNull(vault(files).load())
    }

    @Test
    fun `new sign in forgets the collection of the previous account`() = runBlocking {
        val files = newFiles()
        val (repository, settingsRepository) = repository(files) { session }
        settingsRepository.setCollectionUrl(CalendarProvider.YANDEX, "https://caldav.yandex.ru/old/")

        repository.signInWithYandex()

        assertNull(settingsRepository.collectionUrl(CalendarProvider.YANDEX).first())
    }

    @Test
    fun `sign out removes the account and the token`() = runBlocking {
        val files = newFiles()
        val (repository, _) = repository(files) { session }
        repository.signInWithYandex()

        repository.signOut(CalendarProvider.YANDEX)

        assertNull(repository.accountFor(CalendarProvider.YANDEX))
        assertTrue(repository.connectedProviders().isEmpty())
        assertNull(repository.getAccessToken())
        assertNull(vault(files).load())
    }

    @Test
    fun `failed sign in keeps the previous account and repeats the reason`() = runBlocking {
        var online = true
        val files = newFiles()
        val (repository, _) = repository(files) {
            if (online) session else error("Нет сети")
        }
        repository.signInWithYandex()
        online = false

        val result = repository.signInWithYandex()

        assertTrue(result.isFailure)
        assertEquals("Нет сети", result.exceptionOrNull()?.message)
        assertEquals(account, repository.accountFor(CalendarProvider.YANDEX))
        assertEquals("token-1", repository.getAccessToken())
        assertEquals("token-1", vault(files).load())
    }

    @Test
    fun `cancelled sign in stays a cancellation`() = runBlocking {
        val (repository, _) = repository(newFiles()) {
            throw CancellationException("пользователь закрыл диалог")
        }

        try {
            repository.signInWithYandex()
            throw AssertionError("ожидалась отмена, а не Result.failure")
        } catch (expected: CancellationException) {
            assertNull(repository.accountFor(CalendarProvider.YANDEX))
        }
    }

    @Test
    fun `google is unsupported and has no token`() = runBlocking {
        val (repository, _) = repository(newFiles()) { session }

        val result = repository.connectGoogleCalendar()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("не поддерживается"))
        assertNull(repository.getAccessToken(CalendarProvider.GOOGLE))
        assertNull(repository.getAccessToken(CalendarProvider.LOCAL))
    }

    @Test
    fun `corrupt account file is quarantined and starts empty`() = runBlocking {
        val files = newFiles()
        Files.writeString(files.account, "{ not json")
        val (repository, _) = repository(files) { session }

        assertNull(repository.accountFor(CalendarProvider.YANDEX))
        assertFalse(Files.exists(files.account))
        val backups = Files.list(files.account.parent).use { paths ->
            paths.filter { it.fileName.toString().startsWith("account.json.corrupt-") }.toList()
        }
        assertEquals(1, backups.size)
    }
}

private class ReversingCipher : TokenCipher {

    override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()

    override fun decrypt(blob: ByteArray): ByteArray = blob.reversedArray()
}
