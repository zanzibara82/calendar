package com.example.mycalendar.desktop.data

import com.example.mycalendar.data.auth.AccessTokenProvider
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.UserAccount
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.CalendarConnection
import com.example.mycalendar.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Результат входа в Яндекс: токен и учётная запись, под которой он выдан. */
data class YandexSession(
    val token: String,
    val account: UserAccount,
)

/**
 * [AuthRepository] desktop-клиента: Яндекс и Google независимы.
 *
 * Яндекс входит по коду устройства, Google — через браузер (loopback + PKCE);
 * оба входа вынесены в колбэки, чтобы репозиторий не знал про UI и HTTP.
 * Аккаунты лежат в `account.json`, токены — в двух отдельных файлах под DPAPI:
 * выход из одного календаря не трогает другой.
 *
 * Токен без учётной записи бессмыслен: после сбоя выхода он остался бы в файле,
 * поэтому при восстановлении сначала сверяется `account.json`, а осиротевший
 * токен стирается.
 */
class DesktopAuthRepository(
    private val store: DesktopAuthStore,
    private val settingsRepository: SettingsRepository,
    private val tokenVault: TokenVault,
    private val googleTokens: DesktopGoogleTokens,
    private val authorizeYandex: suspend () -> YandexSession,
    private val authorizeGoogle: suspend () -> GoogleSession,
) : AuthRepository, AccessTokenProvider {

    private val managedProviders = listOf(CalendarProvider.GOOGLE, CalendarProvider.YANDEX)

    @Volatile
    private var yandexToken: String? = null

    override val connections: Flow<List<CalendarConnection>> = flow {
        emit(store.snapshot())
        emitAll(store.accounts.filterNotNull())
    }.map { accounts ->
        managedProviders.map { provider ->
            CalendarConnection(provider = provider, account = accounts.readAccount(provider))
        }
    }

    override suspend fun accountFor(provider: CalendarProvider): UserAccount? =
        store.snapshot().readAccount(provider)

    override suspend fun connectedProviders(): List<CalendarProvider> =
        managedProviders.filter { store.snapshot().readAccount(it) != null }

    override suspend fun connectGoogleCalendar(): Result<UserAccount> = try {
        val session = authorizeGoogle()
        saveAccount(session.account)
        // Вход состоялся и без сохранения токена: сбой диска не должен его отменять.
        runCatching { googleTokens.save(session.tokens) }
            .onFailure { System.err.println("google token was not saved: ${it.message}") }
        Result.success(session.account)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(
            IllegalStateException(
                error.message ?: "Не удалось войти через Google: ${error.javaClass.simpleName}"
            )
        )
    }

    override suspend fun signInWithYandex(): Result<UserAccount> = try {
        val session = authorizeYandex()
        yandexToken = session.token
        saveAccount(session.account)
        runCatching { tokenVault.save(session.token) }
            .onFailure { System.err.println("token was not saved: ${it.message}") }
        Result.success(session.account)
    } catch (error: CancellationException) {
        // Отмена (закрыли диалог с кодом, ушли с экрана) — не ошибка входа.
        throw error
    } catch (error: Throwable) {
        Result.failure(
            IllegalStateException(
                error.message ?: "Не удалось войти через Яндекс: ${error.javaClass.simpleName}"
            )
        )
    }

    /**
     * Возвращает сохранённые токены после перезапуска — каждый только при
     * наличии своей учётной записи. Осиротевший токен стирается.
     */
    suspend fun restoreTokens() {
        if (store.snapshot().containsKey(CalendarProvider.YANDEX.name)) {
            yandexToken = tokenVault.load()
        } else {
            yandexToken = null
            tokenVault.clear()
        }
        if (store.snapshot().containsKey(CalendarProvider.GOOGLE.name)) {
            googleTokens.restore()
        } else {
            googleTokens.clear()
        }
    }

    override suspend fun signOut(provider: CalendarProvider) {
        store.update { accounts -> accounts - provider.name }
        when (provider) {
            CalendarProvider.YANDEX -> {
                yandexToken = null
                tokenVault.clear()
            }
            CalendarProvider.GOOGLE -> googleTokens.clear()
            CalendarProvider.LOCAL -> Unit
        }
    }

    override suspend fun signOutAll() {
        managedProviders.forEach { signOut(it) }
    }

    override suspend fun getAccessToken(provider: CalendarProvider, forceRefresh: Boolean): String? =
        when (provider) {
            CalendarProvider.GOOGLE -> googleTokens.accessToken(forceRefresh)
            CalendarProvider.YANDEX -> yandexToken
            CalendarProvider.LOCAL -> null
        }

    /** [AccessTokenProvider] глазами CalDAV-источника: там бывает только Яндекс. */
    override fun getAccessToken(): String? = yandexToken

    private suspend fun saveAccount(account: UserAccount) {
        store.update { accounts ->
            accounts + (account.provider.name to StoredAccount(account.email, account.displayName))
        }
        // Новое подключение открывает новую эпоху доверия: привязка к коллекции прежней
        // учётной записи не должна решать, что считать удалённым на сервере.
        settingsRepository.clearCollectionUrl(account.provider)
    }
}

private fun Map<String, StoredAccount>.readAccount(provider: CalendarProvider): UserAccount? =
    this[provider.name]?.let { stored ->
        UserAccount(provider = provider, email = stored.email, displayName = stored.displayName)
    }
