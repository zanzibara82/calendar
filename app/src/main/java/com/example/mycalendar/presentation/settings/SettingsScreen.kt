package com.example.mycalendar.presentation.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.mycalendar.R
import com.example.mycalendar.data.auth.OAuthConfig
import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ThemeMode
import com.example.mycalendar.domain.repository.SyncResult
import com.example.mycalendar.presentation.components.ALARM_SOUND_ROW_TAG
import com.example.mycalendar.presentation.components.BIRTHDAY_SOUND_ROW_TAG
import com.example.mycalendar.presentation.components.QUIET_MODE_SWITCH_TAG
import com.example.mycalendar.presentation.components.providerColor
import com.example.mycalendar.presentation.common.shortDateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import com.example.mycalendar.presentation.theme.AppSizes
import com.example.mycalendar.presentation.theme.AppSpacing

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
    val updateState by viewModel.updateState.collectAsState()

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
                .padding(AppSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.cardVPad),
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
                        modifier = Modifier.padding(AppSpacing.screen),
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

            // ---------- Обновления ----------
            SectionHeader(stringResource(R.string.updates_section))
            UpdateCard(
                state = updateState,
                onCheck = viewModel::checkForUpdates,
                onInstall = viewModel::downloadAndInstall,
                onOpenInstallSettings = viewModel::openInstallPermissionSettings,
            )

            HorizontalDivider()

            // ---------- Напоминания ----------
            SectionHeader(stringResource(R.string.reminders_section))
            RemindersCard(
                quietMode = state.quietMode,
                alarmSoundUri = state.alarmSoundUri,
                birthdaySoundUri = state.birthdaySoundUri,
                onQuietChange = viewModel::setQuietMode,
                onAlarmPick = viewModel::setAlarmSoundUri,
                onBirthdayPick = viewModel::setBirthdaySoundUri,
            )

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

    // Системный интент (установщик или экран разрешения) запускает экран:
    // у ViewModel нет доступа к Activity.
    val pendingIntent by viewModel.pendingIntent.collectAsState()
    LaunchedEffect(pendingIntent) {
        val intent = pendingIntent ?: return@LaunchedEffect
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            viewModel.onPendingIntentFailed(e.message)
        } finally {
            viewModel.consumePendingIntent()
        }
    }

    val showHelp by viewModel.showHelp.collectAsState()
    if (showHelp) {
        OAuthSetupDialog(onDismiss = { viewModel.dismissSetupHelp() })
    }
}

/**
 * Карточка «Обновления»: текущая версия, проверка GitHub Releases и установка.
 *
 * Проверка идёт разово по кнопке — фонового автообновления нет: приложение
 * не должно само ходить в сеть без действия пользователя.
 */
@Composable
private fun UpdateCard(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onOpenInstallSettings: () -> Unit,
) {
    val busy = state.status == UpdateUiState.UpdateStatus.CHECKING ||
        state.status == UpdateUiState.UpdateStatus.DOWNLOADING

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AppSpacing.screen)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SystemUpdate, contentDescription = null)
                Spacer(modifier = Modifier.width(AppSpacing.cardVPad))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.update_current_version,
                            state.currentVersion,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            val statusText = when (state.status) {
                UpdateUiState.UpdateStatus.IDLE,
                UpdateUiState.UpdateStatus.CHECKING -> null

                UpdateUiState.UpdateStatus.UP_TO_DATE ->
                    stringResource(R.string.update_up_to_date)

                UpdateUiState.UpdateStatus.AVAILABLE ->
                    state.info?.let { stringResource(R.string.update_available, it.versionName) }

                UpdateUiState.UpdateStatus.DOWNLOADING ->
                    stringResource(R.string.update_downloading)

                UpdateUiState.UpdateStatus.NO_RELEASES ->
                    stringResource(R.string.update_no_releases)

                UpdateUiState.UpdateStatus.NEEDS_PERMISSION ->
                    stringResource(R.string.update_install_permission)

                UpdateUiState.UpdateStatus.BAD_SIGNATURE ->
                    stringResource(R.string.update_signature_mismatch)

                UpdateUiState.UpdateStatus.FAILED ->
                    stringResource(R.string.update_check_failed, state.message.orEmpty())
            }
            val isError = state.status == UpdateUiState.UpdateStatus.FAILED ||
                state.status == UpdateUiState.UpdateStatus.BAD_SIGNATURE
            statusText?.let { text ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.cardVPad))
            when (state.status) {
                UpdateUiState.UpdateStatus.AVAILABLE,
                UpdateUiState.UpdateStatus.DOWNLOADING -> Button(
                    onClick = onInstall,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.update_download_install)) }

                UpdateUiState.UpdateStatus.NEEDS_PERMISSION -> Button(
                    onClick = onOpenInstallSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.update_allow_install)) }

                else -> OutlinedButton(
                    onClick = onCheck,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.update_check)) }
            }
        }
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
    // Длинное «Синхронизировать» в половине карточки рвётся посреди слова,
    // поэтому подпись держим в одну строку и ужимаем шрифт. Нижняя граница 7sp
    // при системном масштабе 2× даёт те же 14sp — текст остаётся читаемым,
    // а не обрезается.
    val buttonLabelAutoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 14.sp)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AppSpacing.screen)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderIcon(provider = state.provider)
                Spacer(modifier = Modifier.width(AppSpacing.cardVPad))
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

            Spacer(modifier = Modifier.height(AppSpacing.cardVPad))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isConnected) {
                    // «Синхронизировать» — главное действие карточки, поэтому кнопка
                    // шире «Отключить»: при равных долях подпись не влезает в строку.
                    OutlinedButton(
                        onClick = onSync,
                        enabled = !state.isBusy && state.syncAvailable,
                        modifier = Modifier.weight(1.5f),
                        contentPadding = PaddingValues(horizontal = AppSpacing.cardVPad, vertical = 8.dp),
                    ) {
                        if (state.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(AppSizes.iconSmall),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.sync_in_progress),
                                maxLines = 1,
                                autoSize = buttonLabelAutoSize,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.sync_now),
                                maxLines = 1,
                                autoSize = buttonLabelAutoSize,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = AppSpacing.cardVPad, vertical = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.disconnect),
                            maxLines = 1,
                            autoSize = buttonLabelAutoSize,
                        )
                    }
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
 * Иконка провайдера: цвет провайдера из палитры + первая буква.
 * Собственные vector-логотипы надёжнее, но брендовые знаки — торговая марка,
 * поэтому используем нейтральную цветную круглую плашку.
 */
@Composable
private fun ProviderIcon(provider: CalendarProvider, modifier: Modifier = Modifier) {
    val label = when (provider) {
        CalendarProvider.GOOGLE -> "G"
        CalendarProvider.YANDEX -> "Я"
        CalendarProvider.LOCAL -> "Л"
    }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(providerColor(provider)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimary,
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
                Spacer(modifier = Modifier.height(AppSpacing.screen))
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

/** Какая строка открыла пикер — результат маршрутизируется в нужный сеттер. */
private enum class SoundTarget { ALARM, BIRTHDAY }

/**
 * Карточка «Напоминания»: тумблер тихого режима и две строки выбора сигнала с пикером
 * и предпрослушиванием. При активном тихом режиме строки затемнены (enabled = false),
 * выбор сохранён и возвращается после выключения тумблера.
 */
@Composable
private fun RemindersCard(
    quietMode: Boolean,
    alarmSoundUri: String?,
    birthdaySoundUri: String?,
    onQuietChange: (Boolean) -> Unit,
    onAlarmPick: (String?) -> Unit,
    onBirthdayPick: (String?) -> Unit,
) {
    val context = LocalContext.current
    val alarmTitle = stringResource(R.string.alarm_sound)
    val birthdayTitle = stringResource(R.string.birthday_sound)
    var pickerTarget by remember { mutableStateOf(SoundTarget.ALARM) }
    var preview by remember { mutableStateOf<Ringtone?>(null) }

    DisposableEffect(Unit) {
        onDispose { preview?.stop() }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uri = if (Build.VERSION.SDK_INT >= 33) {
                data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            preview?.stop()
            preview = null
            if (pickerTarget == SoundTarget.ALARM) onAlarmPick(uri?.toString()) else onBirthdayPick(uri?.toString())
        }
    }

    val launchPicker: (SoundTarget) -> Unit = { target ->
        val existing = if (target == SoundTarget.ALARM) alarmSoundUri else birthdaySoundUri
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_TITLE,
                if (target == SoundTarget.ALARM) alarmTitle else birthdayTitle,
            )
            if (existing != null) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(existing))
        }
        pickerLauncher.launch(intent)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Отказ не блокирует: работает откат на встроенный сигнал.
        launchPicker(pickerTarget)
    }

    val requestPick: (SoundTarget) -> Unit = { target ->
        pickerTarget = target
        val needsPermission = Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            launchPicker(target)
        }
    }

    val togglePreview: (String?) -> Unit = { uri ->
        val current = preview
        if (current != null && current.isPlaying) {
            current.stop()
            preview = null
        } else if (uri != null) {
            current?.stop()
            val ringtone = RingtoneManager.getRingtone(context, Uri.parse(uri))
            if (ringtone != null) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                ringtone.play()
                preview = ringtone
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AppSpacing.screen)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.quiet_mode), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.quiet_mode_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = quietMode,
                    onCheckedChange = onQuietChange,
                    modifier = Modifier.testTag(QUIET_MODE_SWITCH_TAG),
                )
            }
            Spacer(modifier = Modifier.height(AppSpacing.cardVPad))
            SoundRow(
                label = stringResource(R.string.alarm_sound),
                uri = alarmSoundUri,
                enabled = !quietMode,
                tag = ALARM_SOUND_ROW_TAG,
                previewing = preview?.isPlaying == true,
                onPick = { requestPick(SoundTarget.ALARM) },
                onPreview = { togglePreview(alarmSoundUri) },
            )
            Spacer(modifier = Modifier.height(AppSpacing.cardVPad))
            SoundRow(
                label = stringResource(R.string.birthday_sound),
                uri = birthdaySoundUri,
                enabled = !quietMode,
                tag = BIRTHDAY_SOUND_ROW_TAG,
                previewing = preview?.isPlaying == true,
                onPick = { requestPick(SoundTarget.BIRTHDAY) },
                onPreview = { togglePreview(birthdaySoundUri) },
            )
        }
    }
}

/** Строка выбора сигнала: имя файла (или «Стандартный»/«недоступен») и кнопка play/stop. */
@Composable
private fun SoundRow(
    label: String,
    uri: String?,
    enabled: Boolean,
    tag: String,
    previewing: Boolean,
    onPick: () -> Unit,
    onPreview: () -> Unit,
) {
    val context = LocalContext.current
    val ringtone = remember(uri) { uri?.let { RingtoneManager.getRingtone(context, Uri.parse(it)) } }
    // getTitle читает MediaProvider: битый или недоступный URI бросает исключение — тогда
    // строка показывает «недоступен» вместо падения экрана.
    val title = remember(uri) { ringtone?.let { it -> runCatching { it.getTitle(context) }.getOrNull() } }
    val available = title != null
    val valueText = when {
        uri == null -> stringResource(R.string.sound_default)
        !available -> stringResource(R.string.sound_unavailable)
        else -> title
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(enabled = enabled, onClick = onPick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                valueText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPreview, enabled = enabled && available) {
            Icon(
                if (previewing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (previewing) R.string.sound_preview_stop else R.string.sound_preview_play
                ),
            )
        }
    }
}
