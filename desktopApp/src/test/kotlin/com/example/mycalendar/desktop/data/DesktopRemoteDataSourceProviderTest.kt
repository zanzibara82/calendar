package com.example.mycalendar.desktop.data

import com.example.mycalendar.data.remote.RemoteCalendarDataSource
import com.example.mycalendar.data.remote.google.GoogleCalendarDataSource
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

    private class Fixture(
        val provider: DesktopRemoteDataSourceProvider,
        val google: RemoteCalendarDataSource,
        val yandex: RemoteCalendarDataSource,
    )

    private fun newProvider(): Fixture = runBlocking {
        val dir = folder.newFolder().toPath()
        val settings = DesktopSettingsRepository(DesktopSettingsStore(dir.resolve("settings.json")))
        val googleTokens = DesktopGoogleTokens(
            vault = MemoryTokenVault(),
            clientId = "client-id",
            clientSecret = "client-secret",
        )
        val auth = DesktopAuthRepository(
            store = DesktopAuthStore(dir.resolve("account.json")),
            settingsRepository = settings,
            tokenVault = MemoryTokenVault(),
            googleTokens = googleTokens,
            authorizeYandex = {
                YandexSession("token", UserAccount(CalendarProvider.YANDEX, "user@yandex.ru"))
            },
            authorizeGoogle = { error("вход Google не нужен") },
        )
        val yandex = YandexCalendarDataSource(CalDavClient(OkHttpClient()), auth, auth)
        val google = GoogleCalendarDataSource(OkHttpClient(), auth)
        Fixture(DesktopRemoteDataSourceProvider(google, yandex), google, yandex)
    }

    @Test
    fun `google and yandex use their sources`() {
        val fixture = newProvider()

        assertSame(fixture.google, fixture.provider.forProvider(CalendarProvider.GOOGLE))
        assertSame(fixture.yandex, fixture.provider.forProvider(CalendarProvider.YANDEX))
    }

    @Test
    fun `local has no remote source`() {
        assertNull(newProvider().provider.forProvider(CalendarProvider.LOCAL))
    }
}
