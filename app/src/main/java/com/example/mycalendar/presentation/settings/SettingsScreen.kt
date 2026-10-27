package com.example.mycalendar.presentation.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.mycalendar.R
import com.example.mycalendar.data.auth.OAuthConfig
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ThemeMode
import com.example.mycalendar.domain.repository.SyncResult
import com.example.mycalendar.presentation.common.shortDateTimeFormatter
import java.time.Instant
import java.time.ZoneId

/**
 * Экран настроек: тема, подключаемые календари (Google / Яндекс), разрешения.
 *
 * Календари — это НЕ вход в приложение, а отдельные источники событий:
 * подключил -> синхронизировал -> события подтянулись в локальный календарь.
 * Оба провайдера могут быть подключены одновременно и независимо.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenConflicts: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    val context = LocalContext.current

    // Разрешение могло быть выдано в прошлой сессии — проверяем при открытии экрана
    val hasCalendarPermission = remember { mutableStateOf(hasCalendarPermission(context)) }

    // Google подключается через системный календарь: OAuth не нужен, достаточно
    // runtime-разрешения на чтение/запись календаря.
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val allGranted = granted[Manifest.permission.READ_CALENDAR] == true &&
            granted[Manifest.permission.WRITE_CALENDAR] == true
        hasCalendarPermission.value = hasCalendarPermission(context)
        viewModel.onCalendarPermissionResult(allGranted)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- Оформление ----------
            SectionHeader(stringResource(R.string.appearance))
            Text(stringResource(R.string.theme_mode), style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val modes = listOf(
                    ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                    ThemeMode.LIGHT to stringResource(R.string.theme_light),
                    ThemeMode.DARK to stringResource(R.string.theme_dark),
                )
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = state.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    ) { Text(label) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.dynamic_color),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.dynamic_color_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                )
            }

            HorizontalDivider()

            // ---------- Календари ----------
            SectionHeader(stringResource(R.string.calendars_section))
            Text(
                text = stringResource(R.string.calendars_section_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.calendars.forEach { calendar ->
                CalendarCard(
                    state = calendar,
                    onConnect = {
                        when (calendar.provider) {
                            CalendarProvider.GOOGLE ->
                                if (hasCalendarPermission.value) {
                                    viewModel.connectGoogle()
                                } else {
                                    calendarPermissionLauncher.launch(CALENDAR_PERMISSIONS)
                                }

                            CalendarProvider.YANDEX -> viewModel.connectYandex()

                            CalendarProvider.LOCAL -> Unit
                        }
                    },
                    onDisconnect = { viewModel.disconnect(calendar.provider) },
                    onSync = { viewModel.sync(calendar.provider) },
                )
            }

            val anyConnected = state.calendars.any { it.isConnected }
            Button(
                onClick = viewModel::syncAll,
                enabled = anyConnected && state.calendars.none { it.isBusy },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.CloudSync, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.sync_all))
            }

            if (state.conflictCount > 0) {
                Card(
                    onClick = onOpenConflicts,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.conflicts_count, state.conflictCount),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // Как работает подключение: системный календарь vs OAuth
            Text(
                text = stringResource(R.string.google_system_calendar_help),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Почему Яндекс недоступен: без client_id его OAuth вернёт HTTP 400
            if (!OAuthConfig.isYandexConfigured) {
                Text(
                    text = stringResource(R.string.oauth_not_configured_yandex),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            // ---------- Разрешения ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onOpenPermissions, modifier = Modifier.padding(4.dp)) {
                    Icon(Icons.Filled.Security, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.permissions))
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { viewModel.showSetupHelp() }, modifier = Modifier.padding(4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.oauth_setup_title))
                }
            }
        }
    }

    val showHelp by viewModel.showHelp.collectAsState()
    if (showHelp) {
        OAuthSetupDialog(onDismiss = { viewModel.dismissSetupHelp() })
    }
}

/**
 * Карточка одного календаря: иконка провайдера, статус подключения,
 * кнопки «Подключить/Отключить» и «Синхронизировать».
 */
@Composable
private fun CalendarCard(
    state: CalendarConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSync: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderIcon(provider = state.provider)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = providerName(state.provider),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (state.isConnected) {
                            state.account?.email.orEmpty()
                        } else {
                            stringResource(R.string.calendar_not_connected)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            if (state.isConnected) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (state.lastSyncTime == 0L) {
                        stringResource(R.string.last_sync_never)
                    } else {
                        stringResource(R.string.last_sync, formatDateTime(state.lastSyncTime))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Яндекс синхронизируется поверх CalDAV — кнопка «Синхронизировать»
                // активна при подключённом аккаунте (syncAvailable = true).
                // При ошибке (например, истёк токен) результат виден в блоке
                // результата ниже.
            }

            // Результат последней операции (подключение или синхронизация)
            state.result?.let { result ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = result.describe(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.success) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isConnected) {
                    OutlinedButton(
                        onClick = onSync,
                        enabled = !state.isBusy && state.syncAvailable,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.sync_in_progress))
                        } else {
                            Text(stringResource(R.string.sync_now))
                        }
                    }
                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.disconnect)) }
                } else {
                    Button(
                        onClick = onConnect,
                        enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.connect)) }
                }
            }
        }
    }
}

/**
 * Иконка провайдера: фирменный цвет бренда + первая буква.
 * Собственные vector-логотипы надёжнее, но брендовые знаки — торговая марка,
 * поэтому используем нейтральную цветную круглую плашку.
 */
@Composable
private fun ProviderIcon(provider: CalendarProvider, modifier: Modifier = Modifier) {
    val (label, color) = when (provider) {
        CalendarProvider.GOOGLE -> "G" to Color(0xFF4285F4) // Google Blue
        CalendarProvider.YANDEX -> "Я" to Color(0xFFFF0000) // Yandex Red
        CalendarProvider.LOCAL -> "Л" to Color(0xFF9E9E9E)
    }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun providerName(provider: CalendarProvider): String = when (provider) {
    CalendarProvider.GOOGLE -> stringResource(R.string.provider_google)
    CalendarProvider.YANDEX -> stringResource(R.string.provider_yandex)
    CalendarProvider.LOCAL -> stringResource(R.string.provider_local)
}

/** Текстовое описание результата синхронизации для пользователя. */
@Composable
private fun SyncResult.describe(): String = when {
    !success && message == "not_connected" -> stringResource(R.string.sync_not_connected)
    !success -> stringResource(R.string.sync_failed, message ?: "")
    message?.startsWith("partial:") == true ->
        stringResource(R.string.sync_partial, message!!.removePrefix("partial:"))
    else -> stringResource(R.string.sync_done, pushed, pulled, conflicts)
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Пошаговая инструкция регистрации OAuth-клиентов. */
@Composable
private fun OAuthSetupDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        title = { Text(stringResource(R.string.oauth_setup_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.provider_google),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.oauth_setup_google),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.provider_yandex),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.oauth_setup_yandex),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

/** Разрешения, необходимые для чтения и записи системного календаря. */
private val CALENDAR_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR,
)

private fun hasCalendarPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

private fun formatDateTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(shortDateTimeFormatter)
