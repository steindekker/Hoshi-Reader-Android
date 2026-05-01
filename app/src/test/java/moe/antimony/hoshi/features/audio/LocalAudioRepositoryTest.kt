package moe.antimony.hoshi.features.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class LocalAudioRepositoryTest {
    @Test
    fun exposesPrivateDatabasePath() {
        val filesDir = Files.createTempDirectory("hoshi-local-audio-internal").toFile()
        val repository = LocalAudioRepository(filesDir)

        assertEquals(filesDir.resolve(AudioSettings.LocalAudioPath), repository.dbFile)
        assertNull(repository.databaseSizeBytes())
    }

    @Test
    fun readsPrivateDatabaseSizeWhenPresent() {
        val filesDir = Files.createTempDirectory("hoshi-local-audio-internal").toFile()
        val database = filesDir.resolve(AudioSettings.LocalAudioPath)
        database.parentFile?.mkdirs()
        database.writeBytes("private database".toByteArray())

        val repository = LocalAudioRepository(filesDir)

        assertEquals(database, repository.dbFile)
        assertEquals(database.length(), repository.databaseSizeBytes())
    }

    @Test
    fun replacesDatabaseOnlyAfterCompleteCopy() {
        val filesDir = Files.createTempDirectory("hoshi-local-audio-internal").toFile()
        val repository = LocalAudioRepository(filesDir)
        val progress = mutableListOf<LocalAudioImportProgress>()

        val copied = repository.replacePrivateDatabase(
            input = ByteArrayInputStream("new database".toByteArray()),
            expectedSizeBytes = "new database".length.toLong(),
            onProgress = { progress += it },
        )

        assertEquals("new database".length.toLong(), copied)
        assertEquals("new database", repository.dbFile.readText())
        assertEquals(copied, repository.databaseSizeBytes())
        assertTrue(progress.any { it.copiedBytes == copied && it.totalBytes == copied })
        assertNull(repository.dbFile.parentFile?.resolve("${repository.dbFile.name}.tmp")?.takeIf { it.exists() })
    }

    @Test
    fun keepsExistingDatabaseWhenCopyIsIncomplete() {
        val filesDir = Files.createTempDirectory("hoshi-local-audio-internal").toFile()
        val repository = LocalAudioRepository(filesDir)
        repository.dbFile.parentFile?.mkdirs()
        repository.dbFile.writeText("old database")

        val result = runCatching {
            repository.replacePrivateDatabase(
                input = ByteArrayInputStream("short".toByteArray()),
                expectedSizeBytes = 10,
            )
        }

        assertTrue(result.isFailure)
        assertEquals("old database", repository.dbFile.readText())
        assertNull(repository.dbFile.parentFile?.resolve("${repository.dbFile.name}.tmp")?.takeIf { it.exists() })
    }
}
