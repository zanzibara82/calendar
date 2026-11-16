package com.example.mycalendar.desktop.data

import com.example.mycalendar.desktop.auth.DesktopGoogleOAuthConfig
import com.example.mycalendar.domain.model.UserAccount
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** Токены Google в том виде, в каком они лежат в файле под DPAPI. */
@Serializable
data class GoogleTokens(
    val access: String,
    val refresh: String,
    val expiresAt: Long,
)

/** Успешный вход Google: токены и учётная запись, под которой они выданы. */
data class GoogleSession(
    val tokens: GoogleTokens,
    val account: UserAccount,
)

/** Google больше не принимает refresh-токен: нужен новый вход. */
class GoogleAuthExpiredException(message: String) : Exception(message)

/**
 * Токены Google между запусками: отдельный файл `google-token.bin` под тем же
 * DPAPI-шифром, что и токен Яндекса. Провайдеры независимы: выход из Яндекса
 * не трогает Google и наоборот.
 *
 * Refresh выполняется под мьютексом: параллельные pull/push не должны дважды
 * обменивать один refresh-токен. Если Google не прислал новый refresh,
 * сохраняется прежний — иначе следующее обновление потеряет сессию.
 */
class DesktopGoogleTokens(
    private val vault: TokenVault,
    private val clientId: String,
    private val clientSecret: String,
    private val tokenUrl: String = DesktopGoogleOAuthConfig.TOKEN_URL,
    private val http: OkHttpClient = OkHttpClient(),
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile
    private var cached: GoogleTokens? = null

    suspend fun restore() {
        mutex.withLock { restoreLocked() }
    }

    suspend fun save(tokens: GoogleTokens) {
        mutex.withLock {
            cached = tokens
            vault.save(json.encodeToString(tokens))
        }
    }

    suspend fun clear() {
        mutex.withLock { clearLocked() }
    }

    /** Access-токен; при истечении обновляется, `forceRefresh` — после 401. */
    suspend fun accessToken(forceRefresh: Boolean = false): String? = mutex.withLock {
        val tokens = cached ?: restoreLocked() ?: return@withLock null
        val fresh = !forceRefresh && tokens.expiresAt - now() > REFRESH_MARGIN_MILLIS
        if (fresh) tokens.access else refreshLocked(tokens).access
    }

    private suspend fun restoreLocked(): GoogleTokens? {
        cached?.let { return it }
        val raw = vault.load() ?: return null
        val tokens = runCatching { json.decodeFromString<GoogleTokens>(raw) }.getOrNull()
        if (tokens == null || tokens.access.isBlank() || tokens.refresh.isBlank()) {
            // Чужой или повреждённый файл: вход повторится, но не упадёт.
            vault.clear()
            return null
        }
        cached = tokens
        return tokens
    }

    private suspend fun refreshLocked(tokens: GoogleTokens): GoogleTokens {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", tokens.refresh)
            .build()
        val request = Request.Builder().url(tokenUrl).post(body).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = runCatching {
                    json.decodeFromString<TokenErrorResponse>(text).error
                }.getOrNull()
                if (error == "invalid_grant") {
                    clearLocked()
                    throw GoogleAuthExpiredException(
                        "Сессия Google истекла — войдите заново в Настройках"
                    )
                }
                throw IllegalStateException("Не удалось обновить токен Google: HTTP ${response.code}")
            }
            val parsed = runCatching {
                json.decodeFromString<TokenRefreshResponse>(text)
            }.getOrNull() ?: throw IllegalStateException("Google вернул неожиданный ответ при обновлении токена")
            if (parsed.access.isBlank()) {
                throw IllegalStateException("Google вернул пустой access-токен")
            }
            val next = GoogleTokens(
                access = parsed.access,
                refresh = parsed.refresh?.takeIf { it.isNotBlank() } ?: tokens.refresh,
                expiresAt = now() + TimeUnit.SECONDS.toMillis(parsed.expiresIn),
            )
            cached = next
            vault.save(json.encodeToString(next))
            return next
        }
    }

    private suspend fun clearLocked() {
        cached = null
        vault.clear()
    }

    @Serializable
    private data class TokenRefreshResponse(
        @SerialName("access_token") val access: String = "",
        @SerialName("refresh_token") val refresh: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 3600,
    )

    @Serializable
    private data class TokenErrorResponse(val error: String? = null)

    private companion object {
        const val REFRESH_MARGIN_MILLIS = 60_000L
    }
}
