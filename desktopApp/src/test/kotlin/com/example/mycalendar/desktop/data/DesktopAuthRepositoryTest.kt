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
        val googleToken: Path,
    )

    private fun newFiles(): TempPaths {
        val dir = folder.newFolder().toPath()
        return TempPaths(
            account = dir.resolve("account.json"),
            settings = dir.resolve("settings.json"),
            token = dir.resolve("token.bin"),
            googleToken = dir.resolve("google-token.bin"),
        )
    }

    private fun vault(files: TempPaths) = DesktopTokenVault(files.token, ReversingCipher())

    private fun googleTokens(files: TempPaths) = DesktopGoogleTokens(
        vault = DesktopTokenVault(files.googleToken, ReversingCipher()),
        clientId = "client-id",
        clientSecret = "client-secret",
        tokenUrl = "http://127.0.0.1:9/token",
    )

    private fun repository(
        files: TempPaths,
        authorizeYandex: suspend () -> YandexSession,
        authorizeGoogle: suspend () -> GoogleSession = { error("вход Google не нужен") },
    ): Pair<DesktopAuthRepository, DesktopSettingsRepository> {
        val settingsRepository = DesktopSettingsRepository(DesktopSettingsStore(files.settings))
        val repository = DesktopAuthRepository(
            store = DesktopAuthStore(files.account),
            settingsRepository = settingsRepository,
            tokenVault = vault(files),
            googleTokens = googleTokens(files),
            authorizeYandex = authorizeYandex,
            authorizeGoogle = authorizeGoogle,
        )
        return repository to settingsRepository
    }

    @Test
    fun `sign in stores the account and hands out the token`() = runBlocking {
        val files = newFiles()
        val (repository, _) = repository(files, authorizeYandex = { session })

        val result = repository.signInWithYandex()

        assertEquals(account, result.getOrNull())
        assertEquals(account, repository.accountFor(CalendarProvider.YANDEX))
        assertEquals(listOf(CalendarProvider.YANDEX), repository.connectedProviders())
        assertEquals("token-1", repository.getAccessToken())
        assertEquals("token-1", repository.getAccessToken(CalendarProvider.YANDEX))
        assertEquals("token-1", vault(files).load())
        val connection = repository.connections.first().single {
            it.provider == CalendarProvider.YANDEX
        }
        assertEquals(CalendarProvider.YANDEX, connection.provider)
        assertEquals(account, connection.account)
    }

    @Test
    fun `account and token survive reload`() = runBlocking {
        val files = newFiles()
        val (first, _) = repository(files, authorizeYandex = { session })
        first.signInWithYandex()

        val (reloaded, _) = repository(files, authorizeYandex = { error("вход не нужен") })
        reloaded.restoreTokens()

        assertEquals(account, reloaded.accountFor(CalendarProvider.YANDEX))
        assertEquals("token-1", reloaded.getAccessToken())
    }

    @Test
    fun `token without an account is dropped on restore`() = runBlocking {
        val files = newFiles()
        vault(files).save("stray")
        val (repository, _) = repository(files, authorizeYandex = { session })

        repository.restoreTokens()

        assertNull(repository.getAccessToken())
        assertNull(vault(files).load())
    }

    @Test
    fun `new sign in forgets the collection of the previous account`() = runBlocking {
        val files = newFiles()
        val (repository, settingsRepository) = repository(files, authorizeYandex = { session })
        settingsRepository.setCollectionUrl(CalendarProvider.YANDEX, "https://caldav.yandex.ru/old/")

        repository.signInWithYandex()

        assertNull(settingsRepository.collectionUrl(CalendarProvider.YANDEX).first())
    }

    @Test
    fun `sign out removes the account and the token`() = runBlocking {
        val files = newFiles()
        val (repository, _) = repository(files, authorizeYandex = { session })
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
        val (repository, _) = repository(files, authorizeYandex = {
            if (online) session else error("Нет сети")
        })
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
        val (repository, _) = repository(newFiles(), authorizeYandex = {
            throw CancellationException("пользователь закрыл диалог")
        })

        try {
            repository.signInWithYandex()
            throw AssertionError("ожидалась отмена, а не Result.failure")
        } catch (expected: CancellationException) {
            assertNull(repository.accountFor(CalendarProvider.YANDEX))
        }
    }

    @Test
    fun `google sign in stores the account and the tokens`() = runBlocking {
        val files = newFiles()
        val googleAccount = UserAccount(
            provider = CalendarProvider.GOOGLE,
            email = "user@gmail.com",
            displayName = "Пользователь Google",
        )
        val googleSession = GoogleSession(
            tokens = GoogleTokens("access-g", "refresh-g", expiresAt = Long.MAX_VALUE),
            account = googleAccount,
        )
        val (repository, _) = repository(
            files,
            authorizeYandex = { error("Яндекс не нужен") },
            authorizeGoogle = { googleSession },
        )

        val result = repository.connectGoogleCalendar()

        assertEquals(googleAccount, result.getOrNull())
        assertEquals(googleAccount, repository.accountFor(CalendarProvider.GOOGLE))
        assertEquals(listOf(CalendarProvider.GOOGLE), repository.connectedProviders())
        assertEquals("access-g", repository.getAccessToken(CalendarProvider.GOOGLE))
        assertEquals("access-g", googleTokens(files).accessToken())
    }

    @Test
    fun `google and yandex are independent`() = runBlocking {
        val files = newFiles()
        val googleAccount = UserAccount(CalendarProvider.GOOGLE, "user@gmail.com")
        val googleSession = GoogleSession(
            tokens = GoogleTokens("access-g", "refresh-g", expiresAt = Long.MAX_VALUE),
            account = googleAccount,
        )
        val (repository, _) = repository(
            files,
            authorizeYandex = { session },
            authorizeGoogle = { googleSession },
        )
        repository.signInWithYandex()
        repository.connectGoogleCalendar()

        repository.signOut(CalendarProvider.GOOGLE)

        assertEquals(account, repository.accountFor(CalendarProvider.YANDEX))
        assertEquals("token-1", repository.getAccessToken(CalendarProvider.YANDEX))
        assertNull(repository.accountFor(CalendarProvider.GOOGLE))
        assertNull(repository.getAccessToken(CalendarProvider.GOOGLE))
        assertNull(googleTokens(files).accessToken())
    }

    @Test
    fun `google tokens without an account are dropped on restore`() = runBlocking {
        val files = newFiles()
        googleTokens(files).save(GoogleTokens("access", "refresh", expiresAt = Long.MAX_VALUE))
        val (repository, _) = repository(files, authorizeYandex = { session })

        repository.restoreTokens()

        assertNull(repository.getAccessToken(CalendarProvider.GOOGLE))
        assertNull(googleTokens(files).accessToken())
    }

    @Test
    fun `corrupt account file is quarantined and starts empty`() = runBlocking {
        val files = newFiles()
        Files.writeString(files.account, "{ not json")
        val (repository, _) = repository(files, authorizeYandex = { session })

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
