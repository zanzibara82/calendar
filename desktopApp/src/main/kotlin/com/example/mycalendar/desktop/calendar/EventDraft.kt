package com.example.mycalendar.desktop.calendar

import com.example.mycalendar.domain.model.Event
import com.example.mycalendar.domain.model.RepeatRule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/** Поля, на которые диалог редактора показывает ошибку. */
enum class DraftField { TITLE, DATE, START, END }

/**
 * Текстовый черновик события: дата и время вводятся строками,
 * поэтому проверка полей живёт отдельно от UI и покрыта JVM-тестами.
 */
data class EventDraft(
    val title: String = "",
    val date: String,
    val start: String,
    val end: String,
    val repeatRule: RepeatRule? = null,
)

data class DraftResult(val event: Event?, val errors: Map<DraftField, String>) {
    val isValid: Boolean get() = event != null
}

private val DATE_PATTERN = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)
private val TIME_PATTERN = DateTimeFormatter.ofPattern("H:mm")
private val DISPLAY_TIME_PATTERN = DateTimeFormatter.ofPattern("HH:mm")

/** Новое событие: выбранный день и часовой слот по умолчанию. */
fun draftForNew(date: LocalDate): EventDraft = EventDraft(
    title = "",
    date = date.format(DATE_PATTERN),
    start = "09:00",
    end = "10:00",
)

/** Поля для правки: дата и время показаны в зоне пользователя. */
fun draftFromEvent(event: Event, zone: ZoneId): EventDraft {
    val start = Instant.ofEpochMilli(event.startTime).atZone(zone)
    val end = Instant.ofEpochMilli(event.endTime).atZone(zone)
    return EventDraft(
        title = event.title,
        date = start.toLocalDate().format(DATE_PATTERN),
        start = start.toLocalTime().format(DISPLAY_TIME_PATTERN),
        end = end.toLocalTime().format(DISPLAY_TIME_PATTERN),
        repeatRule = event.repeatRule,
    )
}

/**
 * Собирает событие из черновика. Поля, которых в диалоге нет (описание, заметки,
 * метки, провайдер), берутся из [base]; новое событие создаётся локальным.
 */
fun buildEvent(
    draft: EventDraft,
    id: String,
    base: Event? = null,
    zone: ZoneId = ZoneId.systemDefault(),
): DraftResult {
    val errors = mutableMapOf<DraftField, String>()

    val title = draft.title.trim()
    if (title.isEmpty()) {
        errors[DraftField.TITLE] = "Введите название"
    }
    val date = parse(draft.date) { LocalDate.parse(it, DATE_PATTERN) }
    if (date == null) {
        errors[DraftField.DATE] = "Дата в формате дд.мм.гггг"
    }
    val start = parse(draft.start) { LocalTime.parse(it, TIME_PATTERN) }
    if (start == null) {
        errors[DraftField.START] = "Время в формате ЧЧ:ММ"
    }
    val end = parse(draft.end) { LocalTime.parse(it, TIME_PATTERN) }
    if (end == null) {
        errors[DraftField.END] = "Время в формате ЧЧ:ММ"
    }
    if (start != null && end != null && !end.isAfter(start)) {
        errors[DraftField.END] = "Конец должен быть позже начала"
    }
    if (date == null || start == null || end == null || errors.isNotEmpty()) {
        return DraftResult(event = null, errors = errors)
    }

    val startTime = date.atTime(start).atZone(zone).toInstant().toEpochMilli()
    val endTime = date.atTime(end).atZone(zone).toInstant().toEpochMilli()
    val event = (base ?: Event(id = id, title = title, startTime = startTime, endTime = endTime))
        .copy(
            title = title,
            startTime = startTime,
            endTime = endTime,
            repeatRule = draft.repeatRule,
        )
    return DraftResult(event = event, errors = emptyMap())
}

private inline fun <T> parse(text: String, block: (String) -> T): T? =
    try {
        block(text.trim())
    } catch (_: DateTimeParseException) {
        null
    }
