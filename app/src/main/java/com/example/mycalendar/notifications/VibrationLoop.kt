package com.example.mycalendar.notifications

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Зацикленный пульс вибрации тихого режима: 600 мс вибрации, 1200 мс паузы (шаг 1800 мс).
 * На устройствах без вибрации (hasVibrator() == false) молча ничего не делает — оверлей
 * не должен падать на headless-эмуляторе. Остановка обязательна в onDestroy и finishIfRunning,
 * иначе пульс продолжится после «Отложить».
 */
class VibrationLoop(context: Context) {

    private val vibrator: Vibrator? = run {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (v.hasVibrator()) v else null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val pulse = object : Runnable {
        override fun run() {
            vibrator?.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
            handler.postDelayed(this, 1800)
        }
    }

    fun start() {
        if (running || vibrator == null) return
        running = true
        handler.post(pulse)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(pulse)
        vibrator?.cancel()
    }
}
