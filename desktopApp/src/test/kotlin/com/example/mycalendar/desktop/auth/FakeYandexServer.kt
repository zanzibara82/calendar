package com.example.mycalendar.desktop.auth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Локальный двойник эндпоинтов Яндекса для device flow.
 *
 * Ответы выдаются очередью на путь: тест задаёт «авторизация ещё не подтверждена»,
 * затем токен. Запросы запоминаются целиком — тест сверяет порядок, тело формы
 * и заголовок Authorization, не трогая сеть Яндекса.
 */
internal class FakeYandexServer : AutoCloseable {

    data class Recorded(
        val path: String,
        val method: String,
        val body: String,
        val authorization: String?,
    )

    private data class Reply(val status: Int, val body: String)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val replies = ConcurrentHashMap<String, ConcurrentLinkedQueue<Reply>>()

    val requests = CopyOnWriteArrayList<Recorded>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "fake-yandex").apply { isDaemon = true }
        }
        listOf("/device/code", "/token", "/info").forEach { path ->
            server.createContext(path, ::handle)
        }
        server.start()
    }

    fun respond(path: String, status: Int, body: String) {
        replies.getOrPut(path) { ConcurrentLinkedQueue() }.add(Reply(status, body))
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().decodeToString()
        requests += Recorded(
            path = exchange.requestURI.path,
            method = exchange.requestMethod,
            body = body,
            authorization = exchange.requestHeaders.getFirst("Authorization"),
        )
        val reply = replies[exchange.requestURI.path]?.poll()
            ?: Reply(500, """{"error":"no_reply"}""")
        val bytes = reply.body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)
}
