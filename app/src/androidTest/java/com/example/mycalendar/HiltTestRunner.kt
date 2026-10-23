package com.example.mycalendar

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Тестовый раннер: подменяет Application на HiltTestApplication (требование Hilt-тестов)
 * и заранее инициализирует WorkManager.
 *
 * В продакшене WorkManager инициализируется лениво через MyCalendarApp
 * (Configuration.Provider). В тестовом процессе Application — HiltTestApplication,
 * поэтому инициализируем WorkManager здесь: иначе первый же вызов
 * WorkManager.getInstance (ReminderScheduler при сохранении события) упадёт
 * с "WorkManager is not initialized".
 */
class HiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        ensureWorkManagerInitialized(targetContext)
    }

    companion object {
        fun ensureWorkManagerInitialized(context: Context) {
            try {
                WorkManager.getInstance(context)
            } catch (e: IllegalStateException) {
                WorkManager.initialize(context, Configuration.Builder().build())
            }
        }
    }
}
