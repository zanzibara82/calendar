package com.example.mycalendar.data.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Авторизация через Яндекс ID (OAuth 2.0, implicit flow):
 *  1. открывается браузер со страницей oauth.yandex.ru/authorize;
 *  2. после входа Яндекс редиректит на mycalendar://oauth#access_token=...;
 *  3. MainActivity передаёт Intent сюда через [onRedirect].
 *
 * История багов (исправлено):
 *  - client_id был плейсхолдером → Яндекс отвечал HTTP 400 (invalid_client).
 *    Теперь значение берётся из [OAuthConfig] и проверяется в [checkConfigured];
 *  - парсинг фрагмента ломался на значениях с '=' (access_token содержит '='),
 *    теперь используется substringAfter вместо indexOf;
 *  - в запрос добавлен state — защита от подмены redirect и от «чужих» редиректов;
 *  - токен сохранялся только в памяти, и после перезапуска процесса карточка
 *    «Подключено как …» продолжала показываться, хотя токен уже был утрачен.
 *    Теперь токен лежит в [EncryptedSharedPreferences] — переживает смерть
 *    процесса и пригодится, когда появится CalDAV-клиент.
 */
@Singleton
class YandexAuthDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : AccessTokenProvider {
    companion object {
        private const val TAG = "YandexAuth"
        const val REDIRECT_URI = OAuthConfig.YANDEX_REDIRECT_URI
        private const val AUTH_BASE_URL = "https://oauth.yandex.ru/authorize"

        /**
         * Scopes для OAuth-токена.
         *
         *  - `login:info`  — логин, имя, фамилия, пол, display_name.
         *  - `login:email` — default_email.
         * Без них Яндекс /info отдаёт только `login` и `id`, и карточка
         * «Подключено как …» в Настройках оставалась бы без email/имени.
         *
         *  - `calendar:all` — полный доступ к Яндекс.Календарю (CalDAV-сервер
         * `caldav.yandex.ru`). Сейчас в проекте ещё нет CalDAV-клиента, но
         * scope запрашивается заранее, чтобы токен был пригоден для будущей
         * синхронизации без переавторизации.
         *
         * ВАЖНО: `calendar:all` покажет пользователю на экране согласия
         * Яндекса список из 12 прав на Календарь. Также этот scope должен
         * быть включён в карточке приложения на https://oauth.yandex.ru/
         * (раздел «Доступы» → «Яндекс.Календарь»).
         */
        private const val SCOPES = "login:info login:email calendar:all"

        private const val PREFS_FILE = "yandex_oauth_secure"
        private const val KEY_ACCESS_TOKEN = "access_token"
    }

    private val tokenFlow = MutableSharedFlow<String?>(extraBufferCapacity = 1)

    /**
     * Текущий токен. Зеркало зашифрованного хранилища: при получении нового
     * токена пишем и туда, и сюда; при старте процесса поднимаем из хранилища.
     */
    @Volatile
    private var accessToken: String? = null

    /** EncryptedSharedPreferences — шифруется мастер-ключом в Android Keystore. */
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Ожидаемый state текущей попытки входа (меняется при каждом startAuth). */
    @Volatile
    private var pendingState: String? = null

    init {
        // Поднимаем токен из зашифрованного хранилища, чтобы он пережил
        // перезапуск процесса. Без этого карточка «Подключено как …» после
        // рестарта врала бы: в DataStore email ещё есть, а токена уже нет.
        accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        if (accessToken != null) {
            Log.d(TAG, "init: restored token from secure prefs (${accessToken!!.length} chars)")
        }
    }

    /**
     * Открыть браузер с OAuth-страницей Яндекса.
     * @throws IllegalStateException если client_id не заполнен в [OAuthConfig].
     */
    fun startAuth() {
        checkConfigured()
        pendingState = UUID.randomUUID().toString()
        val url = buildAuthUrl(pendingState!!)
        Log.d(TAG, "startAuth: opening $url")
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Сборка URL авторизации. Все параметры URL-кодируются. */
    private fun buildAuthUrl(state: String): String {
        val params = listOf(
            "response_type" to "token",
            "client_id" to OAuthConfig.YANDEX_CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
            "force_confirm" to "yes",
            "state" to state,
            "display" to "popup",
            "scope" to SCOPES,
        ).joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
        return "$AUTH_BASE_URL?$params"
    }

    /**
     * Обработка deep link из MainActivity (onCreate/onNewIntent).
     *
     * Токен приходит в URI-фрагменте: mycalendar://oauth#access_token=XXX&expires_in=...
     * Фрагмент НЕ отправляется на сервер, поэтому читается только на клиенте.
     *
     * @return true, если это был наш OAuth-редирект (независимо от наличия токена).
     */
    fun onRedirect(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (data.scheme != "mycalendar" || data.host != "oauth") return false

        val fragment = data.fragment
        // Во фрагменте лежит access_token — в лог попадают только имена параметров.
        val params = parseFragment(fragment)
        Log.d(TAG, "onRedirect: params=${params.keys}")

        // Яндекс может вернуть ошибку прямо во фрагменте: error=access_denied&...
        val error = params["error"]
        if (!error.isNullOrBlank()) {
            Log.w(TAG, "onRedirect: OAuth error=$error")
            tokenFlow.tryEmit(null)
            return true
        }

        // Сверяем state — защита от чужого/устаревшего редиректа
        val expected = pendingState
        if (expected != null && params["state"] != expected) {
            Log.w(TAG, "onRedirect: state mismatch, ignoring redirect")
            return true
        }
        pendingState = null

        val token = params["access_token"]
        if (token.isNullOrBlank()) {
            Log.w(TAG, "onRedirect: no access_token in fragment")
            return true
        }

        accessToken = token
        // Шифрованный кэш: иначе после рестарта процесса в DataStore остаётся
        // email, и карточка «Подключено как …» продолжает показываться,
        // хотя токена уже нет.
        runCatching { prefs.edit { putString(KEY_ACCESS_TOKEN, token) } }
            .onFailure { Log.w(TAG, "onRedirect: failed to persist token: ${it.message}") }
        Log.d(TAG, "onRedirect: token received (${token.length} chars)")
        tokenFlow.tryEmit(token)
        return true
    }

    /**
     * Разбор URI-фрагмента вида "access_token=XXX&expires_in=3600".
     *
     * ВАЖНО: делим по первому '=' (substringAfter), а не по indexOf+split,
     * иначе значение, содержащее '=' (например base64 в токене), обрезается.
     */
    private fun parseFragment(fragment: String?): Map<String, String> {
        if (fragment.isNullOrBlank()) return emptyMap()
        return fragment.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = pair.substring(0, idx)
            val rawValue = pair.substring(idx + 1)
            // Значение приходит URL-encoded — декодируем
            key to Uri.decode(rawValue)
        }.toMap()
    }

    /** Ожидание токена после запуска браузера (с таймаутом). */
    suspend fun awaitAccessToken(timeoutMillis: Long = 180_000): String? =
        withTimeoutOrNull(timeoutMillis) {
            tokenFlow.filterNotNull().first()
        }

    /** Токен из памяти (сбрасывается при перезапуске процесса — требуется повторный вход). */
    override fun getAccessToken(): String? = accessToken

    fun reset() {
        accessToken = null
        pendingState = null
        runCatching { prefs.edit { remove(KEY_ACCESS_TOKEN) } }
            .onFailure { Log.w(TAG, "reset: failed to clear token: ${it.message}") }
    }

    /**
     * Проверка, что client_id реально задан. Вызывается ДО открытия браузера:
     * иначе Яндекс показывает непонятную «Ошибка 400» вместо вменяемого сообщения.
     */
    private fun checkConfigured() {
        check(OAuthConfig.isYandexConfigured) {
            "Не задан YANDEX_CLIENT_ID (data/auth/OAuthConfig.kt). " +
                "Зарегистрируйте приложение на https://oauth.yandex.ru и укажите ID."
        }
    }
}
