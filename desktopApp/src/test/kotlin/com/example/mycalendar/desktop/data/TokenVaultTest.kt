package com.example.mycalendar.desktop.data

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TokenVaultTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: Path get() = folder.root.toPath().resolve("token.bin")
    private val cipher = TaggedCipher()

    @Test
    fun `saved token is encrypted on disk and loads back`() = runBlocking {
        val vault = DesktopTokenVault(file, cipher)

        vault.save("token-1")

        assertEquals("token-1", vault.load())
        val raw = String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)
        assertFalse(raw.contains("token-1"))
    }

    @Test
    fun `missing file loads null`() = runBlocking {
        assertNull(DesktopTokenVault(file, cipher).load())
    }

    @Test
    fun `clear removes the saved token`() = runBlocking {
        val vault = DesktopTokenVault(file, cipher)
        vault.save("token-1")

        vault.clear()

        assertNull(vault.load())
        assertFalse(Files.exists(file))
    }

    @Test
    fun `unreadable blob is dropped instead of failing the start`() = runBlocking {
        Files.writeString(file, "not our format")

        assertNull(DesktopTokenVault(file, cipher).load())
        assertFalse(Files.exists(file))
    }

    @Test
    fun `blank token is ignored`() = runBlocking {
        val vault = DesktopTokenVault(file, cipher)

        vault.save("   ")

        assertNull(vault.load())
    }
}

private class TaggedCipher : TokenCipher {

    override fun encrypt(plain: ByteArray): ByteArray = byteArrayOf(MAGIC) + plain.reversedArray()

    override fun decrypt(blob: ByteArray): ByteArray {
        if (blob.firstOrNull() != MAGIC) throw IllegalArgumentException("чужой формат")
        return blob.drop(1).toByteArray().reversedArray()
    }

    companion object {
        const val MAGIC: Byte = 0x7e
    }
}
