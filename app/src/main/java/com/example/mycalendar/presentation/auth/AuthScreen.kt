package com.example.mycalendar.presentation.auth

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.mycalendar.R
import kotlinx.coroutines.delay

/**
 * Фоны стартового экрана: при каждом запуске выбирается случайный, чтобы
 * экран не выглядел одинаково. Лежат в `res/drawable-nodpi` (WebP, ~30 КБ
 * каждая) — плотность не нужна, картинка растягивается на весь экран.
 */
private val AUTH_BACKGROUNDS = listOf(
    R.drawable.auth_bg_plans,
    R.drawable.auth_bg_features,
    R.drawable.auth_bg_day_planning,
    R.drawable.auth_bg_all_in_one,
)

/**
 * Стартовый экран: единственная кнопка «Войти».
 *
 * Раньше здесь были «Войти через Google» и «Войти через Яндекс», но вход в
 * приложение и синхронизация календарей — разные вещи: приложение работает
 * локально, а Google/Яндекс подключаются в настройках ради синхронизации.
 */
@Composable
fun AuthScreen(
    onSignedIn: () -> Unit,
    onOpenPermissions: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Случайный фон из четырёх. Обычный remember (НЕ rememberSaveable):
    // сохранённое состояние переживает смерть процесса, и тогда один и тот же
    // фон «залипал» бы на многие запуски. Так картинка меняется при каждом
    // новом открытии экрана; при повороте тоже — это допустимо.
    val background = remember { AUTH_BACKGROUNDS.random() }

    // Автозапрос разрешения на уведомления при ПЕРВОМ запуске (Android 13+):
    // POST_NOTIFICATIONS — рантайм-разрешение, его нельзя выдать «при установке»,
    // поэтому показываем системный диалог сразу на первом экране приложения.
    val notificationsAsked by viewModel.notificationsAsked.collectAsState()
    val notificationsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Фиксируем факт запроса независимо от ответа пользователя
        viewModel.onNotificationsResult()
    }
    LaunchedEffect(notificationsAsked) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAsked) {
            delay(800) // даём первому экрану установиться
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                viewModel.onNotificationsResult()
            } else {
                notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(state.entered) {
        if (state.entered) onSignedIn()
    }

    // Затемняющий градиент поверх картинки: все четыре фона светлые, поэтому
    // без scrim-а текст не читался бы в тёмной теме.
    //
    // Плотность зависит от темы: в светлой теме scrim берётся почти
    // прозрачным (картинка и так светлая — контраст тёмного текста хватает),
    // в тёмной — плотным, иначе светлый текст сливался бы со светлым фоном.
    // Яркость colorScheme.surface считаем вручную, чтобы не зависеть от
    // наличия Color.luminance() в конкретной версии Compose.
    val surface = MaterialTheme.colorScheme.surface
    val surfaceIsLight = (0.2126f * surface.red + 0.7152f * surface.green + 0.0722f * surface.blue) > 0.5f
    val scrimAlphas = if (surfaceIsLight) {
        listOf(0.30f, 0.45f, 0.55f)
    } else {
        listOf(0.68f, 0.74f, 0.82f)
    }
    val scrimBrush = Brush.verticalGradient(scrimAlphas.map { surface.copy(alpha = it) })

    // Фон под всем экраном: при ContentScale.FillWidth картинка занимает
    // только верхнюю часть (она 2:3 на экране 9:20), а снизу остаётся
    // свободная полоса. Без подложки там бы просвечивал фон окна.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(surface),
    ) {
        // ContentScale.FillWidth + TopCenter: иллюстрация видна целиком
        // по ширине, не обрезается по бокам. По высоте занимает ~67% экрана.
        Image(
            painter = painterResource(background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.TopCenter,
        )
        Box(modifier = Modifier.fillMaxSize().background(scrimBrush))

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = Color.Transparent,
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.auth_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.auth_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(56.dp))

                Button(
                    onClick = viewModel::enter,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.enter_app))
                }
                Spacer(modifier = Modifier.height(24.dp))

                TextButton(onClick = onOpenPermissions) {
                    Icon(Icons.Filled.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.permissions))
                }
            }
        }
    }
}
