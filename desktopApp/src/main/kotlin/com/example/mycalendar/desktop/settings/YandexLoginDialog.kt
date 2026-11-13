package com.example.mycalendar.desktop.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.mycalendar.desktop.auth.DeviceCode
import com.example.mycalendar.desktop.theme.LocalDesktopColors

/**
 * Код устройства из OAuth Яндекса: пользователь вводит его на странице входа,
 * приложение опрашивает токен до истечения кода. Браузер открывает кнопка,
 * а не сама программа: так видно, куда именно уходит пользователь.
 */
@Composable
fun YandexLoginDialog(
    code: DeviceCode,
    onCopy: () -> Unit,
    onOpenPage: () -> Unit,
    onCancel: () -> Unit,
    error: String? = null,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Вход через Яндекс") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Откройте страницу входа и введите код — программа дождётся подтверждения.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = code.userCode,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.testTag("login-code"),
                )
                Text(
                    text = code.verificationUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDesktopColors.current.onSurfaceVariant,
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("login-error"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenPage, modifier = Modifier.testTag("login-open-page")) {
                Text("Открыть страницу")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCopy, modifier = Modifier.testTag("login-copy")) {
                    Text("Скопировать код")
                }
                TextButton(onClick = onCancel, modifier = Modifier.testTag("login-cancel")) {
                    Text("Отмена")
                }
            }
        },
    )
}
