package com.example.mycalendar.desktop.data

import com.example.mycalendar.domain.model.CalendarProvider
import com.example.mycalendar.domain.model.ConflictKind
import com.example.mycalendar.domain.model.SyncConflict
import com.example.mycalendar.domain.model.SyncField
import com.example.mycalendar.domain.model.SyncSnapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopConflictRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newRepository(): Pair<DesktopConflictRepository, Path> {
        val file = folder.newFolder().toPath().resolve("conflicts.json")
        return DesktopConflictRepository(DesktopConflictStore(file)) to file
    }

    private fun conflict(
        id: String,
        eventId: String,
        detectedAt: Long,
        title: String = "Облако",
        kind: ConflictKind = ConflictKind.CONTENT,
        contested: Set<SyncField> = setOf(SyncField.TITLE),
    ) = SyncConflict(
        id = id,
        eventId = eventId,
        provider = CalendarProvider.YANDEX,
        remoteId = "remote-$eventId",
        kind = kind,
        base = SyncSnapshot(title = "Предок", startTime = 1L, endTime = 2L),
        remote = SyncSnapshot(title = title, startTime = 1L, endTime = 2L),
        contested = contested,
        detectedAt = detectedAt,
    )

    @Test
    fun `saved conflict survives reload`() = runBlocking {
        val (repository, file) = newRepository()
        val saved = conflict("c1", "e1", detectedAt = 10L)

        repository.save(saved)

        val reloaded = DesktopConflictRepository(DesktopConflictStore(file))
        assertEquals(saved, reloaded.get("c1"))
        assertEquals(1, reloaded.observeOpenCount().first())
    }

    @Test
    fun `second detection keeps the id and the first detection time`() = runBlocking {
        val (repository, _) = newRepository()
        repository.save(conflict("c1", "e1", detectedAt = 10L))

        repository.save(conflict("c2", "e1", detectedAt = 20L, title = "Новое облако"))

        val conflicts = repository.observeConflicts().first()
        assertEquals(1, conflicts.size)
        assertEquals("c1", conflicts.single().id)
        assertEquals(10L, conflicts.single().detectedAt)
        assertEquals("Новое облако", conflicts.single().remote.title)
    }

    @Test
    fun `queue is ordered by detection time`() = runBlocking {
        val (repository, _) = newRepository()
        repository.save(conflict("late", "e3", detectedAt = 30L))
        repository.save(conflict("early", "e1", detectedAt = 10L))
        repository.save(conflict("middle", "e2", detectedAt = 20L))

        val ids = repository.observeConflicts().first().map { it.id }

        assertEquals(listOf("early", "middle", "late"), ids)
        assertEquals(setOf("e1", "e2", "e3"), repository.openEventIds().toSet())
    }

    @Test
    fun `delete removes only the chosen conflict`() = runBlocking {
        val (repository, _) = newRepository()
        repository.save(conflict("c1", "e1", detectedAt = 10L))
        repository.save(conflict("c2", "e2", detectedAt = 20L))

        repository.delete("c1")

        assertNull(repository.get("c1"))
        assertEquals(listOf("e2"), repository.openEventIds())
    }

    @Test
    fun `corrupt file is quarantined and the queue starts empty`() = runBlocking {
        val (repository, file) = newRepository()
        Files.writeString(file, "{ not json")

        assertEquals(emptyList<SyncConflict>(), repository.observeConflicts().first())
        assertFalse(Files.exists(file))
        val backups = Files.list(file.parent).use { paths ->
            paths.filter { it.fileName.toString().startsWith("conflicts.json.corrupt-") }.toList()
        }
        assertEquals(1, backups.size)
    }

    @Test
    fun `no temp files left after write`() = runBlocking {
        val (repository, file) = newRepository()
        repository.save(conflict("c1", "e1", detectedAt = 10L))

        val leftovers = Files.list(file.parent).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".tmp") }.toList()
        }
        assertTrue(leftovers.isEmpty())
    }
}
