package com.example.mycalendar.di

import com.example.mycalendar.data.remote.yandex.CalDavClient
import com.example.mycalendar.data.remote.yandex.YandexLoginApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** JSON с мягкой обработкой полей: API может добавлять новые поля. */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            // Токены передаются в заголовках — тело не логируем
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    /** Retrofit для Яндекс ID Login API (получение email после OAuth). */
    @Provides
    @Singleton
    fun provideYandexLoginApi(client: OkHttpClient, json: Json): YandexLoginApi =
        Retrofit.Builder()
            .baseUrl("https://login.yandex.ru/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(YandexLoginApi::class.java)

    /**
     * CalDAV-клиент Яндекс.Календаря. Базовый URL по умолчанию —
     * `https://caldav.yandex.ru`; юнит-тесты конструируют [CalDavClient] напрямую
     * с mock-URL, поэтому здесь важен только production-вариант.
     */
    @Provides
    @Singleton
    fun provideCalDavClient(client: OkHttpClient): CalDavClient = CalDavClient(client)
}
