package com.example.mycalendar.desktop.data

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Настоящий Windows DPAPI: зашифрованный blob читается только текущим пользователем. */
class DpapiTokenCipherTest {

    private val cipher = DpapiTokenCipher()

    @Test
    fun `dpapi round trip hides the token in the blob`() {
        val token = "yandex-token-1"

        val blob = cipher.encrypt(token.toByteArray(StandardCharsets.UTF_8))

        assertFalse(String(blob, StandardCharsets.ISO_8859_1).contains(token))
        assertEquals(token, String(cipher.decrypt(blob), StandardCharsets.UTF_8))
    }
}
