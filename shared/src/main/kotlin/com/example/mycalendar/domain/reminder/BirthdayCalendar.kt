package com.example.mycalendar.domain.reminder

/**
 * Коллекция дней рождения по имени. У Google имя календаря — displayName системного
 * календаря, у Яндекса — displayname CalDAV-коллекции; единого поля нет, поэтому
 * распознаём по нормализованному имени. «др» — по слову, а не по подстроке:
 * внутри «дровни» оно давало бы ложное срабатывание. Стем «рожд» не используем —
 * он совпал бы с «Рождество» и «Возрождение».
 */
object BirthdayCalendar {

    fun matches(calendarName: String?): Boolean {
        val name = calendarName?.lowercase()?.trim().orEmpty()
        if (name.isEmpty()) return false
        if ("день рожд" in name || "дни рожд" in name) return true
        if ("birthda" in name) return true
        return "др" in name.split(Regex("[^\\p{L}\\p{N}]+"))
    }
}