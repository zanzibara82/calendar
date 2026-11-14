package com.example.mycalendar.desktop.data

import com.example.mycalendar.data.remote.RemoteCalendarDataSource
import com.example.mycalendar.data.remote.yandex.CalDavClient
import com.example.mycalendar.data.remote.yandex.YandexCalendarDataSource
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.UserAccount
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopRemoteDataSourceProviderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newProvider(): Pair<DesktopRemoteDataSourceProvider, RemoteCalendarDataSource> =
        runBlocking {
            val dir = folder.newFolder().toPath()
            val settings = DesktopSettingsRepository(DesktopSettingsStore(dir.resolve("settings.json")))
            val auth = DesktopAuthRepository(
                store = DesktopAuthStore(dir.resolve("account.json")),
                settingsRepository = settings,
                tokenVault = MemoryTokenVault(),
                authorize = {
                    YandexSession("token", UserAccount(CalendarProvider.YANDEX, "user@yandex.ru"))
                },
            )
            val yandex = YandexCalendarDataSource(CalDavClient(OkHttpClient()), auth, auth)
            DesktopRemoteDataSourceProvider(yandex) to yandex
        }

    @Test
    fun `yandex uses the caldav source`() {
        val (provider, yandex) = newProvider()

        assertSame(yandex, provider.forProvider(CalendarProvider.YANDEX))
    }

    @Test
    fun `google and local have no remote source`() {
        val (provider, _) = newProvider()

        assertNull(provider.forProvider(CalendarProvider.GOOGLE))
        assertNull(provider.forProvider(CalendarProvider.LOCAL))
    }
}
