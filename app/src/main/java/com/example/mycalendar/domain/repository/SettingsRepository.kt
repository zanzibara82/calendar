package com.example.mycalendar.domain.repository

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Пользовательские настройки (DataStore).
 */
interface SettingsRepository {
    val themeMode: Flow<ThemeMode>
    val dynamicColor: Flow<Boolean>
    val lastSyncTime: Flow<Long>
    /** Запрашивали ли уже разрешение на уведомления (чтобы не спрашивать повторно). */
    val notificationsAsked: Flow<Boolean>

    /**
     * Пользователь уже зашёл в приложение (нажал «Войти» на стартовом экране).
     * Вход в приложение больше не зависит от Google/Яндекс — это просто локальный
     * старт, поэтому нужен отдельный флаг, а не наличие аккаунта.
     */
    val onboardingCompleted: Flow<Boolean>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setLastSyncTime(timeMillis: Long)
    suspend fun setNotificationsAsked()
    suspend fun setOnboardingCompleted(completed: Boolean = true)

    /**
     * Время последней синхронизации КОНКРЕТНОГО провайдера.
     * Провайдеры синхронизируются независимо, поэтому и время у каждого своё.
     */
    fun lastSyncTime(provider: CalendarProvider): Flow<Long>

    suspend fun setLastSyncTime(provider: CalendarProvider, timeMillis: Long)

    /**
     * URL коллекции, из которой прошла последняя синхронизация провайдера (null — ещё
     * не запоминали). Движок сверяет его с текущим: только одной и той же коллекции
     * можно верить на слово, что пустая выборка означает «удалено всё».
     */
    fun collectionUrl(provider: CalendarProvider): Flow<String?>

    suspend fun setCollectionUrl(provider: CalendarProvider, url: String)

    /**
     * Забыть коллекцию провайдера. Вызывается при новом подключении календаря:
     * привязка относится к прежней учётной записи и не должна пережить её смену,
     * иначе движок навсегда перестанет верить пустой выборке (URL другой коллекции
     * не совпадёт с сохранённым, а перезаписывать его при расхождении нельзя).
     */
    suspend fun clearCollectionUrl(provider: CalendarProvider)
}
