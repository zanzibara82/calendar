package com.example.mycalendar.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVersionTest {

    @Test
    fun `property from build carries the packaged version`() {
        assertEquals("0.2.1", appVersion("0.2.1"))
    }

    @Test
    fun `missing property falls back to dev`() {
        assertEquals("dev", appVersion(null))
    }

    @Test
    fun `blank property falls back to dev`() {
        assertEquals("dev", appVersion("   "))
    }
}
