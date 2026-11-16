package com.example.mycalendar.desktop.auth

import com.example.mycalendar.domain.model.CalendarProvider
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopGoogleLoginTest {

    @Test
    fun `pkce challenge matches the rfc 7636 vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            googleCodeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `sign in returns tokens and the account`() = runBlocking {
        FakeGoogleAuthServer().use { server ->
            var capturedAuthUrl: String? = null
            val session = login(server).signIn { url ->
                capturedAuthUrl = url
                visitCallback(url, state = null, code = "code-1")
            }

            val auth = capturedAuthUrl!!.toHttpUrl()
            assertEquals("code", auth.queryParameter("response_type"))
            assertEquals("offline", auth.queryParameter("access_type"))
            assertEquals("consent", auth.queryParameter("prompt"))
            assertEquals("S256", auth.queryParameter("code_challenge_method"))
            assertEquals(
                googleCodeChallenge(server.lastVerifier!!),
                auth.queryParameter("code_challenge"),
            )

            assertEquals("access-1", session.tokens.access)
            assertEquals("refresh-1", session.tokens.refresh)
            assertEquals(1_000L + 3_600_000L, session.tokens.expiresAt)
            assertEquals(CalendarProvider.GOOGLE, session.account.provider)
            assertEquals("ivan@example.com", session.account.email)
            assertEquals("Иван", session.account.displayName)
            assertTrue(server.sawUserInfo)
        }
    }

    @Test
    fun `state mismatch is rejected before the token request`() = runBlocking {
        FakeGoogleAuthServer().use { server ->
            val error = runCatching {
                login(server).signIn { url -> visitCallback(url, state = "чужой", code = "code-1") }
            }.exceptionOrNull()

            assertTrue(error!!.message.orEmpty().contains("Неверный ответ входа"))
            assertEquals(null, server.lastVerifier)
        }
    }

    @Test
    fun `browser failure stops the login`() = runBlocking {
        FakeGoogleAuthServer().use { server ->
            val error = runCatching { login(server).signIn { false } }.exceptionOrNull()

            assertTrue(error!!.message.orEmpty().contains("браузер"))
        }
    }

    @Test
    fun `unanswered callback times out`() = runBlocking {
        FakeGoogleAuthServer().use { server ->
            val error = runCatching {
                login(server, timeoutMillis = 300).signIn { true }
            }.exceptionOrNull()

            assertTrue(error!!.message.orEmpty().contains("не завершён"))
        }
    }

    private fun login(server: FakeGoogleAuthServer, timeoutMillis: Long = 10_000L) =
        DesktopGoogleLogin(
            clientId = "client-id",
            clientSecret = "client-secret",
            authUrl = server.url + "/authorize",
            tokenUrl = server.url + "/token",
            userInfoUrl = server.url + "/userinfo",
            http = OkHttpClient(),
            timeoutMillis = timeoutMillis,
            now = { 1_000L },
        )

    /** Имитация браузера: приходит на redirect_uri с кодом и state из auth-URL. */
    private fun visitCallback(authUrl: String, state: String?, code: String): Boolean {
        val url = authUrl.toHttpUrl()
        val redirect = url.queryParameter("redirect_uri")!!
        val realState = state ?: url.queryParameter("state")!!
        val target = "$redirect?code=${URLEncoder.encode(code, "UTF-8")}" +
            "&state=${URLEncoder.encode(realState, "UTF-8")}"
        val connection = URI(target).toURL().openConnection() as HttpURLConnection
        val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
        stream?.use { it.readBytes() }
        return true
    }

    /** Сервер Google: авторизация не нужна, обменивает код и отдаёт профиль. */
    private class FakeGoogleAuthServer : java.io.Closeable {

        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var lastVerifier: String? = null
            private set
        var sawUserInfo: Boolean = false
            private set

        val url: String get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/token") { exchange ->
                val form = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                lastVerifier = form.substringAfter("code_verifier=", "").substringBefore('&')
                val body = """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600}"""
                respond(exchange, 200, body)
            }
            server.createContext("/userinfo") { exchange ->
                sawUserInfo = exchange.requestHeaders.getFirst("Authorization") == "Bearer access-1"
                respond(exchange, 200, """{"email":"ivan@example.com","name":"Иван"}""")
            }
            server.start()
        }

        private fun respond(exchange: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        override fun close() {
            server.stop(0)
        }
    }
}
