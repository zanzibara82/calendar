package com.example.mycalendar.desktop.auth

import com.example.mycalendar.desktop.data.GoogleSession
import com.example.mycalendar.desktop.data.GoogleTokens
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.UserAccount
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** S256-преобразование code_verifier в code_challenge (RFC 7636). */
internal fun googleCodeChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

/**
 * Вход Google для desktop: loopback-redirect + PKCE.
 *
 * Установленное приложение не может использовать device flow для календарных
 * scope, зато клиент типа «Desktop app» принимает redirect на любой порт
 * 127.0.0.1. Порт выбирает ОС, `state` защищает от чужого колбэка, браузер
 * получает обычную страницу «вернитесь в программу».
 */
class DesktopGoogleLogin(
    private val clientId: String = DesktopGoogleOAuthConfig.clientId,
    private val clientSecret: String = DesktopGoogleOAuthConfig.clientSecret,
    private val scope: String = DesktopGoogleOAuthConfig.SCOPES,
    private val authUrl: String = DesktopGoogleOAuthConfig.AUTH_URL,
    private val tokenUrl: String = DesktopGoogleOAuthConfig.TOKEN_URL,
    private val userInfoUrl: String = DesktopGoogleOAuthConfig.USER_INFO_URL,
    private val http: OkHttpClient = OkHttpClient(),
    private val timeoutMillis: Long = TimeUnit.MINUTES.toMillis(10),
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun signIn(openBrowser: (String) -> Boolean): GoogleSession {
        require(clientId.isNotBlank() && clientSecret.isNotBlank()) {
            "Вход в Google не настроен: нет client_id в local.properties"
        }
        val verifier = randomVerifier()
        val state = UUID.randomUUID().toString()
        val callback = CompletableDeferred<Callback>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/oauth2callback") { exchange ->
            finishCallback(exchange, state, callback)
        }
        server.start()
        try {
            val redirect = "http://127.0.0.1:${server.address.port}/oauth2callback"
            if (!openBrowser(authorizationUrl(redirect, googleCodeChallenge(verifier), state))) {
                throw IllegalStateException("Не удалось открыть браузер для входа в Google")
            }
            val result = try {
                withTimeout(timeoutMillis) { callback.await() }
            } catch (timeout: TimeoutCancellationException) {
                throw IllegalStateException("Вход через Google не завершён — попробуйте ещё раз")
            }
            when (result) {
                is Callback.Failed -> throw IllegalStateException(result.message)
                is Callback.Code -> return withContext(Dispatchers.IO) {
                    exchange(result.code, redirect, verifier)
                }
            }
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun finishCallback(
        exchange: HttpExchange,
        expectedState: String,
        callback: CompletableDeferred<Callback>,
    ) {
        val query = exchange.requestURI.rawQuery.orEmpty()
        val code = queryValue(query, "code")
        val error = queryValue(query, "error")
        val state = queryValue(query, "state")
        when {
            error != null -> {
                respond(exchange, 400, "Вход отменён. Вернитесь в программу.")
                callback.complete(Callback.Failed("Вход через Google отменён"))
            }
            state != expectedState -> {
                respond(exchange, 400, "Неверный ответ входа. Попробуйте ещё раз.")
                callback.complete(Callback.Failed("Неверный ответ входа"))
            }
            code == null -> {
                respond(exchange, 400, "Google не прислал код входа.")
                callback.complete(Callback.Failed("Google не прислал код входа"))
            }
            else -> {
                respond(exchange, 200, "Вход завершён. Вернитесь в программу и закройте вкладку.")
                callback.complete(Callback.Code(code))
            }
        }
    }

    private fun authorizationUrl(redirect: String, challenge: String, state: String): String =
        authUrl.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirect)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", scope)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .addQueryParameter("access_type", "offline")
            .addQueryParameter("prompt", "consent")
            .build()
            .toString()

    private fun exchange(code: String, redirect: String, verifier: String): GoogleSession {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirect)
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("code_verifier", verifier)
            .build()
        val tokenText = post(tokenUrl, body)
        val token = json.decodeFromString<TokenResponse>(tokenText)
        val refresh = token.refresh?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Google не выдал долговременный токен — попробуйте войти ещё раз")
        if (token.access.isBlank()) {
            throw IllegalStateException("Google вернул пустой access-токен")
        }
        val account = fetchAccount(token.access)
        return GoogleSession(
            tokens = GoogleTokens(
                access = token.access,
                refresh = refresh,
                expiresAt = now() + TimeUnit.SECONDS.toMillis(token.expiresIn),
            ),
            account = account,
        )
    }

    private fun fetchAccount(accessToken: String): UserAccount {
        val request = Request.Builder()
            .url(userInfoUrl)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Не удалось получить профиль Google: HTTP ${response.code}")
            }
            val info = json.decodeFromString<UserInfo>(response.body?.string().orEmpty())
            val email = info.email?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Google не вернул e-mail")
            return UserAccount(
                provider = CalendarProvider.GOOGLE,
                email = email,
                displayName = info.name?.takeIf { it.isNotBlank() } ?: email,
            )
        }
    }

    private fun post(url: String, body: FormBody): String {
        val request = Request.Builder().url(url).post(body).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Google отклонил вход: HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun randomVerifier(): String {
        val bytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun queryValue(query: String, name: String): String? =
        query.split('&')
            .firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { java.net.URLDecoder.decode(it, StandardCharsets.UTF_8) }

    private sealed interface Callback {
        data class Code(val code: String) : Callback
        data class Failed(val message: String) : Callback
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val access: String = "",
        @SerialName("refresh_token") val refresh: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 3600,
    )

    @Serializable
    private data class UserInfo(val email: String? = null, val name: String? = null)
}
