package com.example.mycalendar.desktop.data

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopGoogleTokensTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class Files(val token: Path)

    private fun files(): Files = Files(folder.newFolder().toPath().resolve("google-token.bin"))

    private fun tokens(files: Files, tokenUrl: String, now: () -> Long = { 1_000L }) =
        DesktopGoogleTokens(
            vault = DesktopTokenVault(files.token, GoogleReversingCipher()),
            clientId = "client-id",
            clientSecret = "client-secret",
            tokenUrl = tokenUrl,
            now = now,
        )

    @Test
    fun `saved token survives reload without refresh`() = runBlocking {
        val files = files()
        tokens(files, "http://127.0.0.1:9/token").apply {
            save(GoogleTokens("access-1", "refresh-1", expiresAt = 10_000_000L))
        }

        val reloaded = tokens(files, "http://127.0.0.1:9/token")

        assertEquals("access-1", reloaded.accessToken())
    }

    @Test
    fun `expired token is refreshed and the new one is stored`() = runBlocking {
        TokenServer(
            """{"access_token":"access-2","expires_in":3600}"""
        ).use { server ->
            val files = files()
            tokens(files, server.url).save(GoogleTokens("access-1", "refresh-1", expiresAt = 1_000L))

            val fresh = tokens(files, server.url).accessToken()

            assertEquals("access-2", fresh)
            assertEquals(
                "grant_type=refresh_token&client_id=client-id&client_secret=client-secret&refresh_token=refresh-1",
                server.lastRequest,
            )
            val again = tokens(files, "http://127.0.0.1:9/token").accessToken()
            assertEquals("access-2", again)
        }
    }

    @Test
    fun `invalid grant clears the session and asks to sign in`() = runBlocking {
        TokenServer("""{"error":"invalid_grant"}""", code = 400).use { server ->
            val files = files()
            tokens(files, server.url).save(GoogleTokens("access-1", "refresh-1", expiresAt = 1_000L))

            val error = runCatching { tokens(files, server.url).accessToken() }.exceptionOrNull()

            assertTrue(error is GoogleAuthExpiredException)
            assertTrue(error!!.message.orEmpty().contains("истекла", ignoreCase = true))
            assertNull(tokens(files, server.url).accessToken())
        }
    }

    @Test
    fun `unreadable file is not fatal`() = runBlocking {
        val files = files()
        java.nio.file.Files.writeString(files.token, "не json")

        assertNull(tokens(files, "http://127.0.0.1:9/token").accessToken())
    }

    @Test
    fun `service error keeps the session`() = runBlocking {
        TokenServer("""{"error":"backend"}""", code = 500).use { server ->
            val files = files()
            tokens(files, server.url).save(GoogleTokens("access-1", "refresh-1", expiresAt = 1_000L))

            val error = runCatching { tokens(files, server.url).accessToken() }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertTrue(error!!.message.orEmpty().contains("HTTP 500"))
        }
    }

    /** Крошечный сервер токена: один POST, ответ задан заранее. */
    private class TokenServer(body: String, private val code: Int = 200) : java.io.Closeable {

        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var lastRequest: String? = null
            private set

        val url: String get() = "http://127.0.0.1:${server.address.port}/token"

        init {
            server.createContext("/token") { exchange ->
                lastRequest = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        override fun close() {
            server.stop(0)
        }
    }
}

private class GoogleReversingCipher : TokenCipher {
    override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()
    override fun decrypt(blob: ByteArray): ByteArray = blob.reversedArray()
}
