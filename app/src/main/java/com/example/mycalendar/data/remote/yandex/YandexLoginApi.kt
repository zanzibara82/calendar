package com.example.mycalendar.data.remote.yandex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Ответ Яндекс ID Login API: https://login.yandex.ru/info
 */
@Serializable
data class YandexUserInfoDto(
    val login: String? = null,
    val id: String? = null,
    @SerialName("default_email") val defaultEmail: String? = null,
    @SerialName("display_name") val displayName: String? = null,
)

interface YandexLoginApi {
    @GET("info")
    suspend fun getUserInfo(
        @Query("format") format: String = "json",
        @Query("oauth_token") token: String,
    ): YandexUserInfoDto
}
