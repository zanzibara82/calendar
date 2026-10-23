package com.example.mycalendar

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.mycalendar.data.auth.YandexAuthDataSource
import com.example.mycalendar.notifications.NotificationHelper
import com.example.mycalendar.presentation.navigation.AppNavGraph
import com.example.mycalendar.presentation.settings.SettingsViewModel
import com.example.mycalendar.presentation.theme.MyCalendarTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

/**
 * Единственная Activity приложения. Весь UI — Jetpack Compose.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Обработка OAuth-redirect Яндекс ID (mycalendar://oauth#access_token=...). */
    @Inject lateinit var yandexAuthDataSource: YandexAuthDataSource

    /**
     * id события, на которое нужно перейти после старта навигации.
     * Заполняется из [Intent] в [onCreate] и [onNewIntent]; сбрасывается,
     * когда [AppNavGraph] успешно открывает детали.
     * MutableStateFlow нужен, чтобы Compose перезапустил LaunchedEffect при
     * повторном клике «Открыть событие» поверх уже открытого приложения.
     */
    private val pendingEventId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        yandexAuthDataSource.onRedirect(intent)
        pendingEventId.value = intent.getStringExtra(NotificationHelper.EXTRA_EVENT_ID)

        setContent {
            // Тема применяется до первого кадра: значения берутся из DataStore
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val settings by settingsViewModel.uiState.collectAsState()
            val startEventId by pendingEventId.collectAsState()

            MyCalendarTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                AppNavGraph(
                    startEventId = startEventId,
                    onEventOpened = { pendingEventId.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Без setIntent() старый intent останется в activity.intent,
        // и AppNavGraph не увидит новый EXTRA_EVENT_ID.
        setIntent(intent)
        // Deep link при уже открытой Activity (launchMode="singleTask")
        yandexAuthDataSource.onRedirect(intent)
        intent.getStringExtra(NotificationHelper.EXTRA_EVENT_ID)?.let {
            pendingEventId.value = it
        }
    }

    companion object {
        /** Открытие MainActivity на деталях события (из уведомления/аларм-экрана). */
        fun createIntent(
            context: android.content.Context,
            eventId: String?,
        ): Intent = Intent(context, MainActivity::class.java).apply {
            if (eventId != null) {
                putExtra(NotificationHelper.EXTRA_EVENT_ID, eventId)
            }
            // CLEAR_TOP гарантирует доставку onNewIntent в уже работающий
            // экземпляр MainActivity; SINGLE_TOP избегает дубля Activity.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }
}
