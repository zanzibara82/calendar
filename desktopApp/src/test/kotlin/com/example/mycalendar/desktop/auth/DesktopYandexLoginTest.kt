package com.example.mycalendar.desktop.auth

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DesktopYandexLoginTest {

    private lateinit var server: FakeYandexServer

    @Before
    fun setUp() {
        server = FakeYandexServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun login(): DesktopYandexLogin = DesktopYandexLogin(
        clientId = "client-1",
        deviceCodeUrl = "${server.baseUrl}/device/code",
        tokenUrl = "${server.baseUrl}/token",
        userInfoUrl = "${server.baseUrl}/info",
        pollDelay = {},
    )

    private fun deviceCode(
        code: String = "device-1",
        userCode: String = "uo3ub7r5",
        expiresIn: Int = 300,
        interval: Int = 0,
    ) = """
        {"device_code":"$code","user_code":"$userCode",
         "verification_url":"https://ya.ru/device",
         "expires_in":$expiresIn,"interval":$interval}
    """.trimIndent()

    private val tokenOk = """{"access_token":"tok-1","expires_in":31536000,"token_type":"bearer"}"""
    private val tokenPending = """{"error":"authorization_pending","error_description":"Code not confirmed"}"""
    private val userInfo = """
        {"login":"ivanov","default_email":"ivanov@yandex.ru","display_name":"Иван Иванов"}
    """.trimIndent()

    @Test
    fun `polls until token and builds the account`() = runBlocking {
        server.respond("/device/code", 200, deviceCode())
        server.respond("/token", 400, tokenPending)
        server.respond("/token", 200, tokenOk)
        server.respond("/info", 200, userInfo)
        val shown = mutableListOf<DeviceCode>()

        val session = login().signIn { shown += it }

        assertEquals("tok-1", session.token)
        assertEquals("ivanov@yandex.ru", session.account.email)
        assertEquals("Иван Иванов", session.account.displayName)
        assertEquals("uo3ub7r5", shown.single().userCode)
        assertEquals("https://ya.ru/device", shown.single().verificationUrl)
        assertEquals(
            listOf("/device/code", "/token", "/token", "/info"),
            server.requests.map { it.path },
        )
        val tokenRequests = server.requests.filter { it.path == "/token" }
        assertTrue(tokenRequests.all { it.method == "POST" })
        assertTrue(
            tokenRequests.all {
                it.body.contains("grant_type=device_code") && it.body.contains("code=device-1")
            }
        )
        val codeRequest = server.requests.first()
        assertTrue(codeRequest.body.contains("client_id=client-1"))
        assertTrue(codeRequest.body.contains("scope="))
        assertEquals("OAuth tok-1", server.requests.last().authorization)
    }

    @Test
    fun `rejected token explains the server reason`() = runBlocking {
        server.respond("/device/code", 200, deviceCode())
        server.respond(
            "/token",
            400,
            """{"error":"bad_verification_code","error_description":"Код недействителен"}""",
        )

        val failure = runCatching { login().signIn {} }.exceptionOrNull()

        assertTrue(
            "сообщение: ${failure?.message}",
            failure?.message?.contains("Код недействителен") == true,
        )
        assertFalse(server.requests.any { it.path == "/info" })
    }

    @Test
    fun `expired code fails without polling the token`() = runBlocking {
        server.respond("/device/code", 200, deviceCode(expiresIn = 0))

        val failure = runCatching { login().signIn {} }.exceptionOrNull()

        assertTrue("сообщение: ${failure?.message}", failure?.message?.contains("истёк") == true)
        assertFalse(server.requests.any { it.path == "/token" })
    }

    @Test
    fun `unreachable profile endpoint fails the sign in`() = runBlocking {
        server.respond("/device/code", 200, deviceCode())
        server.respond("/token", 200, tokenOk)
        server.respond("/info", 401, """{"error":"invalid_token"}""")

        val failure = runCatching { login().signIn {} }.exceptionOrNull()

        assertTrue("сообщение: ${failure?.message}", failure?.message?.contains("401") == true)
        // Токен не должен попадать в текст ошибки: сообщения видны в интерфейсе и логах.
        assertFalse(failure?.message?.contains("tok-1") == true)
    }

    @Test
    fun `device code request failure names the status`() = runBlocking {
        server.respond("/device/code", 503, """{"error":"unavailable"}""")
        var codeShown = false

        val failure = runCatching { login().signIn { codeShown = true } }.exceptionOrNull()

        assertTrue("сообщение: ${failure?.message}", failure?.message?.contains("503") == true)
        assertFalse(codeShown)
        assertFalse(server.requests.any { it.path == "/token" })
    }

    @Test
    fun `malformed device code response is reported as such`() = runBlocking {
        server.respond("/device/code", 200, """{"user_code":"only-code"}""")

        val failure = runCatching { login().signIn {} }.exceptionOrNull()
        if (failure == null) {
            fail("ожидалась ошибка разбора ответа")
            return@runBlocking
        }
        assertTrue(
            "сообщение: ${failure.message}",
            failure.message?.contains("неожиданный ответ") == true,
        )
    }
}
