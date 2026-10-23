package com.example.mycalendar.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.mycalendar.domain.usecase.RescheduleRemindersUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * После перезагрузки устройства WorkManager сам восстанавливает запланированные работы,
 * но напоминания, запланированные ПОСЛЕ последнего запуска приложения или появившиеся
 * из синхронизации, стоит перестроить: заново планируем все предстоящие события.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var rescheduleReminders: RescheduleRemindersUseCase

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                rescheduleReminders()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
