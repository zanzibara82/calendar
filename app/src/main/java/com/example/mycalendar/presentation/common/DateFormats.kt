package com.example.mycalendar.presentation.common

import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Форматы дат русского интерфейса.
 *
 * Локаль задана явно: на устройстве с другим языком подписи иначе смешались бы,
 * а формат одного и того же вида не должен расходиться между экранами.
 */
internal val russianLocale: Locale = Locale.forLanguageTag("ru")

/** «12 марта 2026, 09:30» — карточка события и разбор конфликта. */
internal val fullDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", russianLocale)

/** «12 мар 2026, 09:30» — очередь конфликтов и настройки. */
internal val shortDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", russianLocale)

/** «12 мар 2026» — редактор события. */
internal val shortDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", russianLocale)

/** «09:30» — редактор события. */
internal val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", russianLocale)

/** «март 2026» — заголовок месяца в календаре. */
internal val monthTitleFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("LLLL yyyy", russianLocale)

/** «12 марта» — подпись дня. */
internal val dayTitleFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM", russianLocale)

/** «четверг, 12 марта» — подпись дня недели. */
internal val weekdayTitleFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE, d MMMM", russianLocale)
