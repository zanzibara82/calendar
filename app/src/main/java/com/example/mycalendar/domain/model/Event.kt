package com.example.mycalendar.domain.model

/**
 * Доменная модель события — единственный источник правды для presentation-слоя.
 * Соответствует модели из ТЗ.
 */
data class Event(
    val id: String,
    val title: String,
    val description: String? = null,
    val startTime: Long,
    val endTime: Long,
    /** Список отступов в минутах до начала события: 5/10/15/30, 60 (1 час), 1440 (1 день). */
    val reminderMinutes: List<Int> = emptyList(),
    /** Правило повторения (daily/weekly/monthly/yearly) или null, если событие одиночное. */
    val repeatRule: RepeatRule? = null,
    val textNote: String? = null,
    /** Метки события: День рождения / Свидание / Кино. */
    val tags: List<EventTag> = emptyList(),
    /** Абсолютный путь к файлу голосовой заметки (AAC/M4A) или null. */
    val voiceNotePath: String? = null,
    /** Провайдер, с которым синхронизировано событие. */
    val calendarProvider: CalendarProvider = CalendarProvider.LOCAL,
    /** Идентификатор события на стороне удалённого провайдера (Google/Yandex). */
    val remoteId: String? = null,
    /** Время последнего изменения (epoch millis) — основа для last-write-wins. */
    val updatedAt: Long = 0L,
)

/**
 * Метки события (выбираются чекбоксами в редакторе).
 */
enum class EventTag {
    BIRTHDAY,
    DATE,
    CINEMA,
}

/** Поддерживаемые провайдеры календаря. */
enum class CalendarProvider {
    /** Только локальное хранение (режим гостя). */
    LOCAL,
    GOOGLE,
    YANDEX,
}

/** Простые правила повторения события. */
enum class RepeatRule {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
}
