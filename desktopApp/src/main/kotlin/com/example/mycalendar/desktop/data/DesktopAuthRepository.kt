package com.example.mycalendar.desktop.data

import com.example.mycalendar.data.auth.AccessTokenProvider
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.UserAccount
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.CalendarConnection
import com.example.mycalendar.domain.repository.SettingsRepository
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
 * подключает Google, здесь нет. Сам вход вынесен в [authorize] — OAuth-редирект
 * на loopback появится отдельным шагом; репозиторий отвечает за то, что и после него
 * остаётся прежним: хранение аккаунта, сброс привязки к коллекции и доступ к токену.
 *
 * Токен НЕ переживает перезапуск процесса: до появления защищённого хранения секретов
 * он живёт в памяти. Аккаунт при этом сохраняется, поэтому после перезапуска вход
 * потребуется повторить, а события и очередь конфликтов не теряются.
 */
class DesktopAuthRepository(
    private val store: DesktopAuthStore,
    private val settingsRepository: SettingsRepository,
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

    override suspend fun signInWithYandex(): Result<UserAccount> = runCatching {
        val session = authorize()
        yandexToken = session.token
        saveAccount(session.account)
        session.account
    }.recoverCatching { error ->
        throw IllegalStateException(
            error.message ?: "Не удалось войти через Яндекс: ${error.javaClass.simpleName}"
        )
    }

    override suspend fun signOut(provider: CalendarProvider) {
        store.update { accounts -> accounts - provider.name }
        if (provider == CalendarProvider.YANDEX) yandexToken = null
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
