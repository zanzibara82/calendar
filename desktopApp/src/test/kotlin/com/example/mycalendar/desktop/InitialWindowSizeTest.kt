package com.example.mycalendar.desktop

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class InitialWindowSizeTest {

    @Test
    fun `desired size fits a large screen unchanged`() {
        val size = initialWindowSize(3840, 2160, scaleX = 1.5f, scaleY = 1.5f)

        assertEquals(1500.dp, size.width)
        assertEquals(1140.dp, size.height)
    }

    @Test
    fun `height is clamped on a full hd screen at 100 percent`() {
        val size = initialWindowSize(1920, 1080)

        assertEquals(1500.dp, size.width)
        assertEquals(1016.dp, size.height)
    }

    @Test
    fun `both dimensions are clamped at 125 percent scaling`() {
        val size = initialWindowSize(1920, 1080, scaleX = 1.25f, scaleY = 1.25f)

        assertEquals(1472.dp, size.width)
        assertEquals(800.dp, size.height)
    }

    @Test
    fun `small screen keeps a margin`() {
        val size = initialWindowSize(800, 600)

        assertEquals(736.dp, size.width)
        assertEquals(536.dp, size.height)
    }
}
