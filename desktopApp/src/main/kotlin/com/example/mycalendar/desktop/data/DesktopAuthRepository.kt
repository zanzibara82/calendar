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
 * [AuthRepository] desktop-клиента.
 *
 * На Windows пока есть только Яндекс: системного календаря, через который Android
 * подключает Google, здесь нет. Сам вход вынесен в [authorize] — в окне это device flow
 * (`DesktopYandexLogin`); репозиторий отвечает за то, что и после него
 * остаётся прежним: хранение аккаунта, сброс привязки к коллекции и доступ к токену.
 *
 * Токен переживает перезапуск: он лежит в [tokenVault] под защитой DPAPI, а при старте
 * возвращается только если учётная запись Яндекса на месте. Выход из аккаунта стирает
 * и файл токена. Аккаунт при этом сохраняется в account.json, поэтому события и очередь
 * конфликтов не теряются.
 */
class DesktopAuthRepository(
    private val store: DesktopAuthStore,
    private val settingsRepository: SettingsRepository,
    private val tokenVault: TokenVault,
    private val authorize: suspend () -> YandexSession,
) : AuthRepository, AccessTokenProvider {

    private val managedProviders = listOf(CalendarProvider.YANDEX)

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

    override suspend fun connectGoogleCalendar(): Result<UserAccount> =
        Result.failure(
            IllegalStateException(
                "Google Календарь на Windows пока не поддерживается: системного календаря нет"
            )
        )

    override suspend fun signInWithYandex(): Result<UserAccount> = try {
        val session = authorize()
        yandexToken = session.token
        saveAccount(session.account)
        // Вход состоялся и без сохранения токена: сбой диска не должен его отменять.
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
     * Возвращает сохранённый токен после перезапуска.
     *
     * Токен без учётной записи бессмыслен и опасен: он остался бы в файле после сбоя
     * выхода. Поэтому сначала сверяется account.json, и осиротевший токен стирается.
     */
    suspend fun restoreToken() {
        if (store.snapshot().containsKey(CalendarProvider.YANDEX.name)) {
            yandexToken = tokenVault.load()
        } else {
            yandexToken = null
            tokenVault.clear()
        }
    }

    override suspend fun signOut(provider: CalendarProvider) {
        store.update { accounts -> accounts - provider.name }
        if (provider == CalendarProvider.YANDEX) {
            yandexToken = null
            tokenVault.clear()
        }
    }

    override suspend fun signOutAll() {
        managedProviders.forEach { signOut(it) }
    }

    override suspend fun getAccessToken(provider: CalendarProvider, forceRefresh: Boolean): String? =
        when (provider) {
            // Google синхронизируется через системный календарь Android; на desktop его нет.
            CalendarProvider.GOOGLE -> null
            CalendarProvider.YANDEX -> yandexToken
            CalendarProvider.LOCAL -> null
        }

    /** [AccessTokenProvider] глазами CalDAV-источника: токен есть только у Яндекса. */
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
