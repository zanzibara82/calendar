package com.example.mycalendar.domain.reminder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Распознавание коллекции дней рождения по имени. Совпадение по нормализованному
 * (lowercase) имени: «день рожд»/«дни рожд», «birthda», отдельное слово «др».
 * «др» проверяется по слову, а не по подстроке: внутри «дровни» оно ложно.
 */
class BirthdayCalendarTest {

    @Test
    fun `plural russian name matches`() {
        assertTrue(BirthdayCalendar.matches("Дни рождения"))
    }

    @Test
    fun `singular russian name with suffix matches`() {
        assertTrue(BirthdayCalendar.matches("День рождения (Яндекс)"))
    }

    @Test
    fun `english name matches`() {
        assertTrue(BirthdayCalendar.matches("Birthdays"))
    }

    @Test
    fun `short abbreviation matches`() {
        assertTrue(BirthdayCalendar.matches("ДР"))
    }

    @Test
    fun `substring inside another word does not match`() {
        assertFalse(BirthdayCalendar.matches("Дровни"))
    }

    @Test
    fun `unrelated name does not match`() {
        assertFalse(BirthdayCalendar.matches("Рабочие встречи"))
    }

    @Test
    fun `null does not match`() {
        assertFalse(BirthdayCalendar.matches(null))
    }
}