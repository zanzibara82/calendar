package com.example.mycalendar.data.update

import com.example.mycalendar.domain.model.UpdateCheck
import com.example.mycalendar.domain.model.UpdateInfo
import com.example.mycalendar.domain.model.isNewerVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Проверка обновлений через GitHub Releases.
 *
 * Репозиторий публичный, поэтому запрос анонимный и только на чтение — токен
 * в приложении не хранится. Ответ 404 означает «релизов ещё нет»: это штатное
 * состояние нового репозитория, а не сбой проверки.
 *
 * APK берётся из первого ассета, оканчивающегося на `.apk`: имя файла задаёт
 * релизный скрипт, а не приложение.
 */
@Singleton
class GitHubReleasesDataSource @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {

    suspend fun checkLatest(currentVersion: String): UpdateCheck =
        checkAt(LATEST_RELEASE_URL, currentVersion)

    /** Тот же запрос по произвольному URL — используется юнит-тестами с локальным сервером. */
    internal suspend fun checkAt(url: String, currentVersion: String): UpdateCheck =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == HTTP_NOT_FOUND -> UpdateCheck.NoReleases
                        !response.isSuccessful -> UpdateCheck.Failed("HTTP ${response.code}")
                        else -> {
                            val body = response.body.string()
                            if (body.isBlank()) {
                                UpdateCheck.Failed("Пустой ответ GitHub")
                            } else {
                                parse(body, currentVersion)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateCheck.Failed(e.message ?: e::class.java.simpleName)
            }
        }

    private fun parse(body: String, currentVersion: String): UpdateCheck {
        val release = json.decodeFromString<GhRelease>(body)
        val version = release.tagName.trim().removePrefix("v").removePrefix("V")
        if (version.isBlank()) return UpdateCheck.Failed("У релиза нет тега версии")

        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return UpdateCheck.Failed("В релизе ${release.tagName} нет APK")

        if (!isNewerVersion(version, currentVersion)) return UpdateCheck.UpToDate

        return UpdateCheck.Available(
            UpdateInfo(
                versionName = version,
                apkUrl = apk.browserDownloadUrl,
                releaseUrl = release.htmlUrl,
                sizeBytes = apk.size,
                notes = release.body.orEmpty().trim(),
            )
        )
    }

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/zanzibara82/calendar/releases/latest"
        const val HTTP_NOT_FOUND = 404
    }
}

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val body: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
private data class GhAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0L,
)
