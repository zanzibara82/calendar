package com.example.mycalendar.presentation.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.mycalendar.R

/**
 * Экран запроса разрешений: уведомления, микрофон, календарь, аккаунты.
 * Всё работает и без них (гостевой/локальный режим) — UI лишь объясняет и запрашивает.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // Счётчик для перечитывания статусов после ответа системы
    var refreshKey by remember { mutableIntStateOf(0) }

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val singlePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        refreshKey++
    }

    // Календарь запрашивается парой READ + WRITE
    val calendarPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshKey++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permissions_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
        ) {
            Text(
                text = stringResource(R.string.permissions_rationale),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Уведомления (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionCard(
                    icon = Icons.Filled.Notifications,
                    title = stringResource(R.string.permission_notifications),
                    description = stringResource(R.string.permission_notifications_desc),
                    granted = remember(refreshKey) { isGranted(Manifest.permission.POST_NOTIFICATIONS) },
                ) {
                    singlePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            // Микрофон
            PermissionCard(
                icon = Icons.Filled.Mic,
                title = stringResource(R.string.permission_mic),
                description = stringResource(R.string.permission_mic_desc),
                granted = remember(refreshKey) { isGranted(Manifest.permission.RECORD_AUDIO) },
            ) {
                singlePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }

            // Календарь (READ + WRITE)
            PermissionCard(
                icon = Icons.Filled.CalendarMonth,
                title = stringResource(R.string.permission_calendar),
                description = stringResource(R.string.permission_calendar_desc),
                granted = remember(refreshKey) {
                    isGranted(Manifest.permission.READ_CALENDAR) &&
                        isGranted(Manifest.permission.WRITE_CALENDAR)
                },
            ) {
                calendarPermissionsLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR,
                    )
                )
            }

            // Точные будильники (Android 12+): специальный доступ "Будильники и напоминания"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                PermissionCard(
                    icon = Icons.Filled.Alarm,
                    title = stringResource(R.string.permission_exact_alarm),
                    description = stringResource(R.string.permission_exact_alarm_desc),
                    granted = remember(refreshKey) { alarmManager.canScheduleExactAlarms() },
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(
                                android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }
                    refreshKey++
                }
            }

            // Уведомления во весь экран (Android 9+): окно поверх локскрина
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val notificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                PermissionCard(
                    icon = Icons.Filled.Fullscreen,
                    title = stringResource(R.string.permission_fullscreen),
                    description = stringResource(R.string.permission_fullscreen_desc),
                    granted = remember(refreshKey) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            notificationManager.canUseFullScreenIntent()
                        } else true
                    },
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                    refreshKey++
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (granted) Icons.Filled.CheckCircle else icon,
                contentDescription = null,
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!granted) {
                TextButton(onClick = onRequest) {
                    Text(stringResource(R.string.grant))
                }
            } else {
                Text(
                    text = stringResource(R.string.granted),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
