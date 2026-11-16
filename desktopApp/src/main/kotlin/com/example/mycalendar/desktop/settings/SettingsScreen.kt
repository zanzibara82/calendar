package com.example.mycalendar.desktop.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.mycalendar.desktop.theme.LocalDesktopColors
import com.example.mycalendar.desktop.theme.ThemeMode
import com.example.mycalendar.domain.model.UserAccount
import java.net.URI

/**
 * Настройки в духе Android-приложения: оформление, календари, напоминания и сведения
 * о программе. Обновления появятся позже.
 */
@Composable
fun SettingsScreen(
    settings: DesktopSettings,
    dataDir: String,
    version: String,
    onBack: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onQuietChange: (Boolean) -> Unit,
    onMinimizeToTrayChange: (Boolean) -> Unit,
    onPickAlarm: () -> Unit,
    onResetAlarm: () -> Unit,
    onPickBirthday: () -> Unit,
    onResetBirthday: () -> Unit,
    autostartSupported: Boolean = false,
    autostartEnabled: Boolean = false,
    autostartError: String? = null,
    onAutostartChange: (Boolean) -> Unit = {},
    account: UserAccount? = null,
    loginError: String? = null,
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    syncStatus: String? = null,
    syncing: Boolean = false,
    onSync: () -> Unit = {},
    conflictCount: Int = 0,
    onOpenConflicts: () -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
) {
    val colors = LocalDesktopColors.current
    val focusRequester = remember { FocusRequester() }
    val stepPx = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    val delta = if (event.type == KeyEventType.KeyDown) {
                        scrollDeltaFor(event.key, scrollState.viewportSize, stepPx)
                    } else {
                        null
                    }
                    if (delta == null) {
                        false
                    } else {
                        scrollState.dispatchRawDelta(delta)
                        true
                    }
                }
                .focusable()
                .testTag("settings-screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("settings-back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Text("Настройки", style = MaterialTheme.typography.headlineSmall)
            }

            SectionHeader("Оформление")
            Panel {
                Text("Тема", style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val modes = listOf(
                        ThemeMode.SYSTEM to "Системная",
                        ThemeMode.LIGHT to "Светлая",
                        ThemeMode.DARK to "Тёмная",
                    )
                    modes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { onThemeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                            modifier = Modifier.testTag("settings-theme-${mode.name.lowercase()}"),
                        ) {
                            Text(label, maxLines = 1)
                        }
                    }
                }
            }

            SectionHeader("Календари")
            Panel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Яндекс", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (account == null) {
                                "Не подключён — вход по коду на ya.ru/device"
                            } else {
                                "Подключено: ${account.displayName ?: account.email}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.testTag("settings-account"),
                        )
                    }
                    if (account == null) {
                        TextButton(onClick = onSignIn, modifier = Modifier.testTag("settings-sign-in")) {
                            Text("Войти")
                        }
                    } else {
                        TextButton(onClick = onSignOut, modifier = Modifier.testTag("settings-sign-out")) {
                            Text("Выйти")
                        }
                    }
                }
                loginError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("settings-login-error"),
                    )
                }
                syncStatus?.let { status ->
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings-sync-status"),
                        )
                        TextButton(
                            onClick = onSync,
                            enabled = account != null && !syncing,
                            modifier = Modifier.testTag("settings-sync"),
                        ) {
                            Text("Синхронизировать")
                        }
                    }
                }
                // Разбор уже открыт в очереди: конфликты без решения блокируют отправку правок.
                if (conflictCount > 0) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings-conflicts"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = "Конфликты: $conflictCount",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = onOpenConflicts,
                            modifier = Modifier.testTag("settings-open-conflicts"),
                        ) {
                            Text("Разобрать")
                        }
                    }
                }
            }

            SectionHeader("Напоминания")
            Panel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Тихий режим", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Напоминания приходят без звука — только на экране.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.quietMode,
                        onCheckedChange = onQuietChange,
                        modifier = Modifier.testTag("settings-quiet"),
                    )
                }
                HorizontalDivider()
                SoundRow(
                    title = "Сигнал напоминания",
                    uri = settings.alarmSoundUri,
                    tag = "alarm",
                    onPick = onPickAlarm,
                    onReset = onResetAlarm,
                )
                HorizontalDivider()
                SoundRow(
                    title = "Сигнал дня рождения",
                    uri = settings.birthdaySoundUri,
                    tag = "birthday",
                    onPick = onPickBirthday,
                    onReset = onResetBirthday,
                )
            }

            SectionHeader("Окно")
            Panel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Сворачивать в трей", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Закрытие окна оставляет программу в трее — напоминания продолжают работать.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Checkbox(
                        checked = settings.minimizeToTray,
                        onCheckedChange = onMinimizeToTrayChange,
                        modifier = Modifier.testTag("settings-minimize-to-tray"),
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Запускать с Windows", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (autostartSupported) {
                                "Программа стартует при входе в систему — напоминания приходят без ручного запуска."
                            } else {
                                "Доступно в установленной версии"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        autostartError?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("settings-autostart-error"),
                            )
                        }
                    }
                    Checkbox(
                        checked = autostartSupported && autostartEnabled,
                        onCheckedChange = onAutostartChange,
                        enabled = autostartSupported,
                        modifier = Modifier.testTag("settings-autostart"),
                    )
                }
            }

            SectionHeader("О приложении")
            Panel {
                Text("MyCalendar $version", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Данные: $dataDir",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.testTag("settings-data-dir"),
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(vertical = 4.dp)
                .testTag("settings-scrollbar"),
            style = ScrollbarStyle(
                minimalHeight = 24.dp,
                thickness = 8.dp,
                shape = RoundedCornerShape(4.dp),
                hoverDurationMillis = 300,
                unhoverColor = colors.onSurfaceVariant.copy(alpha = 0.35f),
                hoverColor = colors.onSurfaceVariant.copy(alpha = 0.6f),
            ),
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = LocalDesktopColors.current.onSurfaceVariant,
    )
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    val colors = LocalDesktopColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SoundRow(
    title: String,
    uri: String?,
    tag: String,
    onPick: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = soundLabel(uri),
                style = MaterialTheme.typography.bodySmall,
                color = LocalDesktopColors.current.onSurfaceVariant,
                modifier = Modifier.testTag("settings-sound-$tag"),
            )
        }
        TextButton(onClick = onPick, modifier = Modifier.testTag("settings-pick-$tag")) {
            Text("Выбрать…")
        }
        TextButton(
            onClick = onReset,
            enabled = uri != null,
            modifier = Modifier.testTag("settings-reset-$tag"),
        ) {
            Text("Сбросить")
        }
    }
}

/** «Системный» для значения по умолчанию, иначе имя выбранного файла. */
internal fun soundLabel(uri: String?): String {
    if (uri == null) return "Системный"
    val name = runCatching { URI(uri).path }.getOrNull()?.substringAfterLast('/')
    return if (name.isNullOrBlank()) uri else name
}
