package com.example.mycalendar.domain.holiday

/**
 * Название праздника платформенно-нейтральным ключом.
 *
 * Домен знает, какой сегодня праздник, но не знает, как он подписан: Android-клиент
 * берёт строку из R.string, desktop — из своего набора. Ключ и есть точка сборки
 * локализации на стороне клиента.
 */
enum class HolidayKey {
    NEW_YEAR_HOLIDAYS,
    CHRISTMAS_ORTHODOX,
    VALENTINES,
    DEFENDER_OF_FATHERLAND,
    WOMENS_DAY,
    SPRING_LABOR,
    VICTORY_DAY,
    CHILDRENS_DAY,
    RUSSIA_DAY,
    HALLOWEEN,
    NATIONAL_UNITY,
    CHRISTMAS_WEST,
    NEW_YEAR_EVE,
}
