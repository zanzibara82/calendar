package com.example.mycalendar.desktop.settings

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollKeysTest {

    @Test
    fun `down and up move by one step`() {
        assertEquals(48f, scrollDeltaFor(Key.DirectionDown, viewportSize = 600, stepPx = 48f))
        assertEquals(-48f, scrollDeltaFor(Key.DirectionUp, viewportSize = 600, stepPx = 48f))
    }

    @Test
    fun `page keys move by almost a viewport`() {
        assertEquals(540f, scrollDeltaFor(Key.PageDown, viewportSize = 600, stepPx = 48f))
        assertEquals(-540f, scrollDeltaFor(Key.PageUp, viewportSize = 600, stepPx = 48f))
    }

    @Test
    fun `other keys are left to the focused control`() {
        assertNull(scrollDeltaFor(Key.Spacebar, viewportSize = 600, stepPx = 48f))
        assertNull(scrollDeltaFor(Key.Tab, viewportSize = 600, stepPx = 48f))
    }
}
