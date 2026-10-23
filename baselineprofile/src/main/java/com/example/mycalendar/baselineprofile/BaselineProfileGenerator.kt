package com.example.mycalendar.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Генератор Baseline Profile для MyCalendar.
 *
 * Запуск:  ./gradlew :app:generateReleaseBaselineProfile
 *
 * Профилируемый путь — ровно то, что видит пользователь при холодном старте:
 *   Splash -> экран входа («Войти») -> календарь («Месяц»).
 * В профиль попадает код запуска приложения (Hilt, Room, DataStore) и первая
 * отрисовка Compose — именно то, что JIT otherwise компилирует на ходу.
 *
 * Тексты кнопок продублированы строковыми литералами: модуль :baselineprofile
 * не зависит от :app, поэтому R.string.* здесь недоступен. При переименовании
 * enter_app / view_month в strings.xml нужно поправить и эти константы.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE_NAME) {
        pressHome()
        // Холодный старт: именно он самый дорогой и именно его ускоряет профиль
        startActivityAndWait()

        // Splash -> экран входа. Если онбординг уже пройден, сразу открывается
        // календарь — тогда просто ждём его.
        device.wait(Until.hasObject(By.text(TEXT_ENTER_APP)), 15_000)
        device.findObject(By.text(TEXT_ENTER_APP))?.click()

        // Ждём отрисовки календаря: значит отработали DI, Room и Compose-разметка
        device.wait(Until.hasObject(By.text(TEXT_VIEW_MONTH)), 15_000)
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE_NAME = "com.example.mycalendar"
        const val TEXT_ENTER_APP = "Войти"   // R.string.enter_app
        const val TEXT_VIEW_MONTH = "Месяц"  // R.string.view_month
    }
}
