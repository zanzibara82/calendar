package com.example.mycalendar

import android.util.Log
import com.example.mycalendar.data.auth.OAuthConfig
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Диагностика авторизации: печатает состояние конфигурации Яндекса.
 *
 * Google-часть удалена вместе с OAuth-кодом: Google синхронизируется через
 * системный календарь (CalendarContract), токен и client_id ему не нужны.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AuthDiagnosticTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun diagnoseYandexConfig() {
        Log.i("AuthDiag", "YANDEX configured=${OAuthConfig.isYandexConfigured}")
        Log.i("AuthDiag", "YANDEX clientId='${OAuthConfig.YANDEX_CLIENT_ID}'")
        Log.i("AuthDiag", "YANDEX redirect='${OAuthConfig.YANDEX_REDIRECT_URI}'")
        // Клиент без redirect URI не доведёт implicit-flow до приложения: Яндекс вернёт
        // токен во фрагмент пустого адреса. Пустая настройка — ошибка конфигурации.
        if (OAuthConfig.isYandexConfigured) {
            assertTrue(
                "При заданном YANDEX_CLIENT_ID нужен непустой YANDEX_REDIRECT_URI",
                OAuthConfig.YANDEX_REDIRECT_URI.isNotBlank(),
            )
        }
    }
}
