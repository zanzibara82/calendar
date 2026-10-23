package com.example.mycalendar.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.mycalendar.data.auth.OAuthConfig
import com.example.mycalendar.data.auth.YandexAuthDataSource
import com.example.mycalendar.data.remote.yandex.YandexLoginApi
import com.example.mycalendar.data.system.SystemCalendarDataSource
import com.example.mycalendar.di.IoDispatcher
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.UserAccount
import com.example.mycalendar.domain.repository.AuthRepository
import com.example.mycalendar.domain.repository.CalendarConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Реализация [AuthRepository]: НЕСКОЛЬКО независимо подключённых календарей.
 *
 * Раньше хранился один «текущий аккаунт», поэтому подключение Яндекса
 * выбивало Google. Теперь у каждого провайдера свои ключи в DataStore:
 * account_google_email / account_yandex_email и т.д.
 *
 * Старые ключи (account_provider + account_email) читаются как fallback,
 * чтобы уже подключённый пользователь не «потерял» аккаунт после обновления.
 *
 * OAuth-токены в память не персистятся: Яндекс-токен живёт до перезапуска
 * процесса (implicit flow). Google токена не требует вообще — он работает
 * через системный календарь (CalendarContract).
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val yandexAuthDataSource: YandexAuthDataSource,
    private val yandexLoginApi: YandexLoginApi,
    private val systemCalendarDataSource: SystemCalendarDataSource,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AuthRepository {

    private object Keys {
        val PROVIDER = stringPreferencesKey("account_provider")      // legacy
        val EMAIL = stringPreferencesKey("account_email")            // legacy
        val DISPLAY_NAME = stringPreferencesKey("account_display_name") // legacy

        fun email(provider: CalendarProvider) =
            stringPreferencesKey("account_${provider.name.lowercase()}_email")

        fun displayName(provider: CalendarProvider) =
            stringPreferencesKey("account_${provider.name.lowercase()}_display_name")
    }

    /**
     * Показываемые в UI провайдеры.
     *
     * Яндекс появляется только когда задан YANDEX_CLIENT_ID: без него OAuth
     * гарантированно вернёт HTTP 400 invalid_client, и карточка лишь вводила
     * пользователя в заблуждение.
     */
    private val managedProviders: List<CalendarProvider> = buildList {
        add(CalendarProvider.GOOGLE)
        if (OAuthConfig.isYandexConfigured) add(CalendarProvider.YANDEX)
    }

    override val connections: Flow<List<CalendarConnection>> = dataStore.data.map { prefs ->
        managedProviders.map { provider ->
            CalendarConnection(provider = provider, account = readAccount(prefs, provider))
        }
    }

    /** Чтение аккаунта провайдера с поддержкой старого формата записи. */
    private fun readAccount(prefs: Preferences, provider: CalendarProvider): UserAccount? {
        val email = prefs[Keys.email(provider)]
            ?: prefs[Keys.EMAIL].takeIf {
                // старые ключи относились к одному аккаунту — берём только
                // если провайдер совпадает
                prefs[Keys.PROVIDER] == provider.name
            }
            ?: return null
        return UserAccount(
            provider = provider,
            email = email,
            displayName = prefs[Keys.displayName(provider)] ?: prefs[Keys.DISPLAY_NAME],
        )
    }

    override suspend fun accountFor(provider: CalendarProvider): UserAccount? =
        dataStore.data.first().let { readAccount(it, provider) }

    override suspend fun connectedProviders(): List<CalendarProvider> =
        dataStore.data.first().let { prefs ->
            managedProviders.filter { readAccount(prefs, it) != null }
        }

    override suspend fun signInWithYandex(): Result<UserAccount> = withContext(ioDispatcher) {
        runCatching {
            // Сразу проверяем конфигурацию: иначе Яндекс покажет непонятную «Ошибка 400»
            if (!OAuthConfig.isYandexConfigured) {
                error(
                    "Вход через Яндекс не настроен: не задан YANDEX_CLIENT_ID " +
                        "в data/auth/OAuthConfig.kt (Callback URI: mycalendar://oauth)."
                )
            }
            yandexAuthDataSource.startAuth()
            val token = yandexAuthDataSource.awaitAccessToken()
                ?: error("Вход через Яндекс отменён или токен не получен")
            val info = yandexLoginApi.getUserInfo(token = token)
            val account = UserAccount(
                provider = CalendarProvider.YANDEX,
                email = info.defaultEmail ?: info.login ?: "yandex-user",
                displayName = info.displayName ?: info.login,
            )
            store(account)
            account
        }.recoverCatching { error ->
            throw IllegalStateException(
                error.message ?: "Не удалось войти через Яндекс: ${error.javaClass.simpleName}"
            )
        }
    }

    override suspend fun signOut(provider: CalendarProvider) {
        dataStore.edit { prefs ->
            prefs.remove(Keys.email(provider))
            prefs.remove(Keys.displayName(provider))
            // старые ключи тоже чистим, чтобы legacy-аккаунт не «воскрес»
            if (prefs[Keys.PROVIDER] == provider.name) {
                prefs.remove(Keys.PROVIDER)
                prefs.remove(Keys.EMAIL)
                prefs.remove(Keys.DISPLAY_NAME)
            }
        }
        if (provider == CalendarProvider.YANDEX) yandexAuthDataSource.reset()
    }

    override suspend fun signOutAll() {
        managedProviders.forEach { signOut(it) }
    }

    override suspend fun connectGoogleCalendar(): Result<UserAccount> = withContext(ioDispatcher) {
        runCatching {
            val calendar = systemCalendarDataSource.selectPrimaryCalendar()
            val account = UserAccount(
                provider = CalendarProvider.GOOGLE,
                email = calendar.accountName.ifBlank { calendar.displayName },
                displayName = calendar.displayName.takeIf { it.isNotBlank() },
            )
            store(account)
            account
        }.recoverCatching { error ->
            throw IllegalStateException(
                error.message ?: "Не удалось подключить Google Календарь: ${error.javaClass.simpleName}"
            )
        }
    }

    override suspend fun getAccessToken(provider: CalendarProvider, forceRefresh: Boolean): String? =
        when (provider) {
            // Google синхронизируется через системный календарь (CalendarContract):
            // OAuth-токен ему не нужен, поэтому всегда null.
            CalendarProvider.GOOGLE -> null

            CalendarProvider.YANDEX -> yandexAuthDataSource.getAccessToken()

            CalendarProvider.LOCAL -> null
        }

    private suspend fun store(account: UserAccount) {
        dataStore.edit { prefs ->
            prefs[Keys.email(account.provider)] = account.email
            account.displayName?.let { prefs[Keys.displayName(account.provider)] = it }
            // legacy-ключи больше не пишем: они только читаются для совместимости
        }
    }
}
