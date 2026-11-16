package com.example.mycalendar.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SingleInstanceTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a second instance is refused while the first holds the lock`() {
        val dir = temp.newFolder().toPath()
        SingleInstance(dir).use { first ->
            assertTrue(first.tryAcquire())
            SingleInstance(dir).use { second ->
                assertFalse(second.tryAcquire())
            }
        }
    }

    @Test
    fun `the lock frees after the holder closes`() {
        val dir = temp.newFolder().toPath()
        SingleInstance(dir).use { first ->
            assertTrue(first.tryAcquire())
        }
        SingleInstance(dir).use { next ->
            assertTrue(next.tryAcquire())
        }
    }

    @Test
    fun `show request is reported once`() {
        val dir = temp.newFolder().toPath()
        SingleInstance(dir).use { primary ->
            assertTrue(primary.tryAcquire())
            SingleInstance(dir).use { secondary ->
                secondary.requestShow()
            }

            val token = primary.pollShowRequest(null)
            assertNotNull(token)
            assertNull(primary.pollShowRequest(token))
        }
    }

    @Test
    fun `every show request gets a fresh token`() {
        val dir = temp.newFolder().toPath()
        SingleInstance(dir).use { guard ->
            guard.requestShow()
            val first = guard.pollShowRequest(null)
            guard.requestShow()
            val second = guard.pollShowRequest(first)

            assertNotNull(first)
            assertNotNull(second)
            assertNotEquals(first, second)
        }
    }
}
