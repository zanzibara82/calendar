package com.example.mycalendar.notifications

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.mycalendar.R
import com.example.mycalendar.presentation.theme.MyCalendarTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Полноэкранный "аларм"-экран напоминания (аналог первой версии приложения).
 *
 * Ключевое поведение:
 *  - запускается через full-screen intent: всплывает в центре экрана и включается
 *    поверх локскрина даже при погашенном экране (showWhenLocked + turnScreenOn);
 *  - ЗАЦИКЛЕННЫЙ звук через MediaPlayer (res/raw/reminder_sound.mp3, USAGE_ALARM):
 *    играет, пока пользователь не нажмёт «Открыть событие» или «Закрыть» —
 *    как в первой версии, где работало.
 */
@AndroidEntryPoint
class ReminderActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: intent=$intent")
        // Показывать и включать экран поверх блокировки (API 27+; для 26 — флаги окна)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // Экран не гаснет, пока открыто окно напоминания
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        runningInstance = this

        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val timeText = intent.getStringExtra(EXTRA_TIME_TEXT).orEmpty()
        val eventId = intent.getStringExtra(NotificationHelper.EXTRA_EVENT_ID)

        startLoopingSound()

        setContent {
            MyCalendarTheme {
                AlarmOverlay(
                    title = title,
                    timeText = timeText,
                    onOpen = {
                        stopLoopingSound()
                        // Даже если id не дошёл (например, из стороннего
                        // уведомления), открываем приложение — иначе кнопка
                        // выглядит «мёртвой» и пользователь не понимает, куда
                        // нажал.
                        startActivity(com.example.mycalendar.MainActivity.createIntent(this, eventId))
                        finish()
                    },
                    onDismiss = {
                        stopLoopingSound()
                        finish()
                    },
                )
            }
        }
    }

    /** Зацикленный сигнал напоминания (тот же принцип, что в первой версии приложения). */
    private fun startLoopingSound() {
        stopLoopingSound()
        Log.d(TAG, "startLoopingSound: attempting to create MediaPlayer for R.raw.reminder_sound")
        try {
            val mp = MediaPlayer.create(this, R.raw.reminder_sound)
            if (mp == null) {
                Log.e(TAG, "  MediaPlayer.create returned null!")
                return
            }
            mp.isLooping = true
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.start()
            mediaPlayer = mp
            Log.d(TAG, "  MediaPlayer started, duration=${mp.duration} ms")
        } catch (e: Exception) {
            Log.e(TAG, "  MediaPlayer creation failed", e)
        }
    }

    private fun stopLoopingSound() {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLoopingSound()
        if (runningInstance === this) runningInstance = null
    }

    companion object {
        private const val TAG = "ReminderActivity"
        const val EXTRA_TITLE = "extra_reminder_title"
        const val EXTRA_TIME_TEXT = "extra_reminder_time_text"

        /**
         * Открытый в данный момент экран напоминания.
         *
         * Нужен [SnoozeReceiver]: кнопка «Отложить» в уведомлении обязана
         * погасить зацикленный сигнал, иначе после откладывания звук продолжит
         * играть из уже ненужного окна. Ссылка живёт только между onCreate
         * и onDestroy, поэтому утечки нет.
         */
        @Volatile
        private var runningInstance: ReminderActivity? = null

        /** Гасит звук и закрывает экран напоминания, если он сейчас на экране. */
        fun finishIfRunning() {
            val activity = runningInstance ?: return
            Log.d(TAG, "finishIfRunning: закрываем экран напоминания по «Отложить»")
            activity.stopLoopingSound()
            activity.finish()
        }
    }
}

@Composable
private fun AlarmOverlay(
    title: String,
    timeText: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Полупрозрачный фон поверх всего (в т.ч. локскрина) + карточка в центре
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.notification_reminder_now),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (timeText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = timeText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.notification_open_event))
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    ) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}
