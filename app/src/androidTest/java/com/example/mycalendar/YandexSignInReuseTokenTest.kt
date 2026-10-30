package com.example.mycalendar

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mycalendar.data.auth.YandexAuthDataSource
import com.example.mycalendar.data.preferences.SettingsRepositoryImpl
import com.example.mycalendar.data.remote.yandex.YandexLoginApi
import com.example.mycalendar.data.remote.yandex.YandexUserInfoDto
import com.example.mycalendar.data.repository.AuthRepositoryImpl
import com.example.mycalendar.data.system.SystemCalendarDataSource
import com.example.mycalendar.domain.model.CalendarProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Вход после опоздавшего OAuth-редиректа: токен уже сохранён (редирект дошёл
 * после таймаута ожидания), но аккаунт не создан. Повторное подключение должно
 * взять сохранённый токен, а не открывать браузер и ждать новый.
 */
@RunWith(AndroidJUnit4::class)
class YandexSignInReuseTokenTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun saved_token_is_reused_without_new_oauth_round_trip() = runBlocking {
        val file = File(context.cacheDir, "auth-reuse-${System.nanoTime()}.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        val yandexAuth = YandexAuthDataSource(context)
        yandexAuth.reset()
        try {
            // Состояние после опоздавшего редиректа: токен в зашифрованном
            // хранилище, записи аккаунта нет.
            val redirect = Intent(
                Intent.ACTION_VIEW,
                "mycalendar://oauth#access_token=late-token&expires_in=3600".toUri(),
            )
            assertTrue(yandexAuth.onRedirect(redirect))

            val api = RecordingLoginApi(
                YandexUserInfoDto(login = "zanzibara", defaultEmail = "backup@yandex.ru"),
            )
            val repository = AuthRepositoryImpl(
                dataStore = dataStore,
                settingsRepository = SettingsRepositoryImpl(dataStore),
                yandexAuthDataSource = yandexAuth,
                yandexLoginApi = api,
                systemCalendarDataSource = SystemCalendarDataSource(
                    context,
                    dataStore,
                    Dispatchers.IO,
                ),
                ioDispatcher = Dispatchers.IO,
            )

            // С новым входом вызов повис бы на awaitAccessToken на десять минут:
            // пять секунд отделяют переиспользование токена от открытия браузера.
            val account = withTimeout(5_000) { repository.signInWithYandex().getOrThrow() }

            assertEquals("backup@yandex.ru", account.email)
            assertEquals(CalendarProvider.YANDEX, account.provider)
            assertEquals(listOf("late-token"), api.requestedTokens)
            assertNotNull(repository.accountFor(CalendarProvider.YANDEX))
        } finally {
            yandexAuth.reset()
            scope.cancel()
            file.delete()
        }
    }

    private class RecordingLoginApi(
        private val response: YandexUserInfoDto,
    ) : YandexLoginApi {
        val requestedTokens = mutableListOf<String>()

        override suspend fun getUserInfo(format: String, token: String): YandexUserInfoDto {
            requestedTokens += token
            return response
        }
    }
}
