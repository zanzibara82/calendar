package com.example.mycalendar.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сравнение версий при проверке обновлений.
 *
 * Наивное сравнение строк ошиблось бы на "1.10" против "1.9" и на тегах с
 * ведущей "v", поэтому проверяем именно числовые сегменты.
 */
class UpdateVersionTest {

    @Test
    fun newerMinorVersionIsOffered() {
        assertTrue(isNewerVersion("1.1", "1.0"))
    }

    @Test
    fun doubleDigitSegmentIsComparedNumerically() {
        assertTrue(isNewerVersion("1.10", "1.9"))
        assertFalse(isNewerVersion("1.9", "1.10"))
    }

    @Test
    fun sameVersionIsNotAnUpdate() {
        assertFalse(isNewerVersion("1.1.0", "1.1.0"))
    }

    @Test
    fun missingTrailingSegmentCountsAsZero() {
        assertFalse(isNewerVersion("1.1", "1.1.0"))
        assertTrue(isNewerVersion("1.1.1", "1.1"))
    }

    @Test
    fun leadingVIsIgnored() {
        assertTrue(isNewerVersion("v1.2", "1.1"))
        assertFalse(isNewerVersion("v1.1.0", "1.1"))
    }

    @Test
    fun prereleaseSuffixIsIgnored() {
        assertTrue(isNewerVersion("1.2-beta", "1.1"))
        assertFalse(isNewerVersion("1.1-beta", "1.1"))
    }

    @Test
    fun nonNumericTagIsTreatedAsDifferentVersion() {
        assertTrue(isNewerVersion("nightly", "1.1"))
    }
}
