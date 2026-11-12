package com.example.mycalendar.desktop.calendar

import com.example.mycalendar.domain.holiday.HolidayKey

/**
 * Подписи праздников desktop-клиента. Android берёт те же тексты из R.string;
 * общий модуль отдаёт только [HolidayKey].
 */
fun holidayTitle(key: HolidayKey): String = when (key) {
    HolidayKey.NEW_YEAR_HOLIDAYS -> "Новогодние каникулы"
    HolidayKey.CHRISTMAS_ORTHODOX -> "Рождество"
    HolidayKey.VALENTINES -> "День святого Валентина"
    HolidayKey.DEFENDER_OF_FATHERLAND -> "День защитника Отечества"
    HolidayKey.WOMENS_DAY -> "Международный женский день"
    HolidayKey.SPRING_LABOR -> "Праздник Весны и Труда"
    HolidayKey.VICTORY_DAY -> "День Победы"
    HolidayKey.CHILDRENS_DAY -> "День защиты детей"
    HolidayKey.RUSSIA_DAY -> "День России"
    HolidayKey.HALLOWEEN -> "Хеллоуин"
    HolidayKey.NATIONAL_UNITY -> "День народного единства"
    HolidayKey.CHRISTMAS_WEST -> "Рождество"
    HolidayKey.NEW_YEAR_EVE -> "Канун Нового года"
}
