package com.example.mycalendar.desktop.auth

import com.example.mycalendar.desktop.data.YandexSession
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.UserAccount
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Код, который пользователь вводит на странице Яндекса. */
data class DeviceCode(
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long = 5,
)

/**
 * Вход через Яндекс ID по коду устройства (OAuth 2.0 Device Authorization Grant).
 *
 * Почему не loopback: у Яндекса нет десктопной платформы при регистрации, адрес возврата
 * пришлось бы вписывать вручную и открывать локальный порт. В этом потоке redirect_uri
 * не участвует вовсе — приложение показывает код, пользователь вводит его на ya.ru/device,
 * а приложение опрашивает токен до истечения кода.
 *
 * Токен возвращается вызывающему и нигде здесь не сохраняется и не пишется в сообщения
 * об ошибках: хранением занимается [com.example.mycalendar.desktop.data.DesktopAuthRepository].
 */
class DesktopYandexLogin(
    private val clientId: String = DesktopOAuthConfig.CLIENT_ID,
    private val scope: String = DesktopOAuthConfig.SCOPES,
    private val deviceCodeUrl: String = DesktopOAuthConfig.DEVICE_CODE_URL,
    private val tokenUrl: String = DesktopOAuthConfig.TOKEN_URL,
    private val userInfoUrl: String = DesktopOAuthConfig.USER_INFO_URL,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val pollDelay: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Запрашивает код и возвращает сессию, когда пользователь подтвердит вход.
     *
     * [onDeviceCode] вызывается до первого опроса токена: интерфейс показывает код
     * и открывает страницу входа. Отмена корутины прекращает опрос.
     */
    suspend fun signIn(onDeviceCode: (DeviceCode) -> Unit): YandexSession {
        val response = requestDeviceCode()
        val code = DeviceCode(
            userCode = response.userCode,
            verificationUrl = response.verificationUrl,
            expiresInSeconds = response.expiresIn,
            intervalSeconds = response.interval,
        )
        onDeviceCode(code)

        val deadline = now() + response.expiresIn * 1000
        while (true) {
            if (now() >= deadline) {
                throw IllegalStateException(
                    "Вход через Яндекс не завершён: код истёк. Нажмите «Войти» ещё раз."
                )
            }
            val poll = pollToken(response.deviceCode)
            if (poll.token != null) return sessionFor(poll.token)
            pollDelay(response.interval * 1000)
        }
    }

    private suspend fun requestDeviceCode(): DeviceCodeResponse {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", scope)
            .build()
        val response = httpClient.execute(Request.Builder().url(deviceCodeUrl).post(body).build())
        response.use {
            if (!it.isSuccessful) {
                throw IllegalStateException(
                    "Не удалось начать вход через Яндекс: HTTP ${it.code}"
                )
            }
            val parsed = runCatching {
                json.decodeFromString<DeviceCodeResponse>(it.body?.string().orEmpty())
            }.getOrNull()
            if (parsed == null || parsed.deviceCode.isBlank() || parsed.userCode.isBlank()) {
                throw IllegalStateException("Яндекс вернул неожиданный ответ при выдаче кода")
            }
            return parsed
        }
    }

    private suspend fun pollToken(deviceCode: String): Poll {
        val body = FormBody.Builder()
            .add("grant_type", "device_code")
            .add("code", deviceCode)
            .build()
        val response = httpClient.execute(Request.Builder().url(tokenUrl).post(body).build())
        response.use {
            val text = it.body?.string().orEmpty()
            if (it.isSuccessful) {
                val token = runCatching { json.decodeFromString<TokenResponse>(text) }.getOrNull()
                if (token == null || token.accessToken.isBlank()) {
                    throw IllegalStateException("Яндекс вернул неожиданный ответ при выдаче токена")
                }
                return Poll(token = token.accessToken)
            }
            val error = runCatching { json.decodeFromString<OAuthError>(text) }.getOrNull()
            // Пока пользователь не подтвердил вход, Яндекс отвечает 400 authorization_pending.
            if (error?.error == "authorization_pending") return Poll(token = null)
            val reason = error?.description ?: error?.error ?: "HTTP ${it.code}"
            throw IllegalStateException("Вход через Яндекс не завершён: $reason")
        }
    }

    private suspend fun sessionFor(token: String): YandexSession {
        val request = Request.Builder()
            .url("$userInfoUrl?format=json")
            .header("Authorization", "OAuth $token")
            .build()
        val response = httpClient.execute(request)
        response.use {
            if (!it.isSuccessful) {
                throw IllegalStateException(
                    "Не удалось получить профиль Яндекса: HTTP ${it.code}"
                )
            }
            val info = runCatching {
                json.decodeFromString<UserInfoResponse>(it.body?.string().orEmpty())
            }.getOrNull()
                ?: throw IllegalStateException("Яндекс вернул неожиданный ответ с профилем")
            val account = UserAccount(
                provider = CalendarProvider.YANDEX,
                email = info.defaultEmail ?: info.login ?: "yandex-user",
                displayName = info.displayName ?: info.login,
            )
            return YandexSession(token = token, account = account)
        }
    }

    private class Poll(val token: String?)

    @Serializable
    private data class DeviceCodeResponse(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_url") val verificationUrl: String,
        @SerialName("expires_in") val expiresIn: Long,
        val interval: Long = 5,
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
    )

    @Serializable
    private data class OAuthError(
        val error: String? = null,
        @SerialName("error_description") val description: String? = null,
    )

    @Serializable
    private data class UserInfoResponse(
        val login: String? = null,
        @SerialName("default_email") val defaultEmail: String? = null,
        @SerialName("display_name") val displayName: String? = null,
    )
}

/** Асинхронный вызов с отменой: отмена корутины обрывает и HTTP-запрос. */
private suspend fun OkHttpClient.execute(request: Request): Response =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }
