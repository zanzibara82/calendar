package com.example.mycalendar.data.update

import com.example.mycalendar.data.remote.yandex.MiniCalDavServer
import com.example.mycalendar.domain.model.UpdateCheck
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Разбор ответа GitHub Releases на локальном HTTP-сервере.
 *
 * Проверяем не сеть, а контракт с API: 404 = «релизов нет», не-2xx = ошибка,
 * ассет выбирается по расширению `.apk`, версия сравнивается числовыми сегментами.
 * Реальный github.com здесь не нужен.
 */
class GitHubReleasesDataSourceTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()

    private fun check(server: MiniCalDavServer, currentVersion: String): UpdateCheck =
        runBlocking {
            GitHubReleasesDataSource(client, json)
                .checkAt("${server.baseUrl}/releases/latest", currentVersion)
        }

    @Test
    fun newerReleaseWithApkIsOffered() {
        val requestedPath = AtomicReference("")
        MiniCalDavServer { request ->
            requestedPath.set(request.path)
            MiniCalDavServer.Response(200, releaseJson(tag = "v1.2.0", apkName = "mycalendar-1.2.0.apk"))
        }.use { server ->
            val result = check(server, currentVersion = "1.1.0")
            val available = result as? UpdateCheck.Available
                ?: throw AssertionError("Ожидался Available, получено $result")

            assertEquals("1.2.0", available.info.versionName)
            assertEquals("https://example.com/mycalendar-1.2.0.apk", available.info.apkUrl)
            assertEquals(
                "https://github.com/zanzibara82/calendar/releases/tag/v1.2.0",
                available.info.releaseUrl,
            )
            assertEquals(42L, available.info.sizeBytes)
            assertEquals("Заметки к релизу", available.info.notes)
        }
        assertEquals("/releases/latest", requestedPath.get())
    }

    @Test
    fun sameVersionIsUpToDate() {
        MiniCalDavServer {
            MiniCalDavServer.Response(200, releaseJson(tag = "v1.1.0"))
        }.use { server ->
            assertEquals(UpdateCheck.UpToDate, check(server, currentVersion = "1.1.0"))
        }
    }

    @Test
    fun missingReleaseIsNotAnError() {
        MiniCalDavServer {
            MiniCalDavServer.Response(404, """{"message":"Not Found"}""")
        }.use { server ->
            assertEquals(UpdateCheck.NoReleases, check(server, currentVersion = "1.1.0"))
        }
    }

    @Test
    fun serverErrorIsReported() {
        MiniCalDavServer {
            MiniCalDavServer.Response(500, "boom")
        }.use { server ->
            val result = check(server, currentVersion = "1.1.0")
            assertEquals(UpdateCheck.Failed("HTTP 500"), result)
        }
    }

    @Test
    fun releaseWithoutApkIsReported() {
        MiniCalDavServer {
            MiniCalDavServer.Response(
                200,
                """
                {
                  "tag_name": "v1.2.0",
                  "html_url": "https://example.com/release",
                  "assets": [{"name": "notes.txt", "browser_download_url": "https://example.com/notes.txt"}]
                }
                """.trimIndent(),
            )
        }.use { server ->
            val result = check(server, currentVersion = "1.1.0")
            assertTrue("Ожидался Failed, получено $result", result is UpdateCheck.Failed)
        }
    }

    @Test
    fun malformedJsonIsReported() {
        MiniCalDavServer {
            MiniCalDavServer.Response(200, "not a json at all")
        }.use { server ->
            val result = check(server, currentVersion = "1.1.0")
            assertTrue("Ожидался Failed, получено $result", result is UpdateCheck.Failed)
        }
    }

    private fun releaseJson(tag: String, apkName: String = "app.apk"): String = """
        {
          "tag_name": "$tag",
          "html_url": "https://github.com/zanzibara82/calendar/releases/tag/$tag",
          "body": "Заметки к релизу",
          "draft": false,
          "prerelease": false,
          "assets": [
            {"name": "notes.txt", "browser_download_url": "https://example.com/notes.txt", "size": 1},
            {"name": "$apkName", "browser_download_url": "https://example.com/$apkName", "size": 42}
          ]
        }
    """.trimIndent()
}
