package moe.antimony.hoshi.features.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageCleanupRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun scanFindsPrivateRestoreImportCacheAndOrphanAudioResidues() {
        val filesDir = temporaryFolder.newFolder("files")
        val cacheDir = temporaryFolder.newFolder("cache")
        filesDir.resolve(".books-restore-deadbeef/part").also { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3))
        }
        filesDir.resolve(".dictionaries-restore-deadbeef.hoshi").writeBytes(byteArrayOf(1, 2))
        filesDir.resolve("ImportTemp/import-a/chapter.xhtml").also { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        filesDir.resolve("Audio/android.db.tmp").also { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }
        val book = filesDir.resolve("Books/Book")
        book.resolve("sasayaki_playback.json").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("""{"lastPosition":0.0,"audioFileName":"sasayaki_audio.m4b"}""")
        }
        book.resolve("Sasayaki/sasayaki_audio.m4b").also { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(byteArrayOf(1))
        }
        book.resolve("Sasayaki/orphan.mp3").writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
        cacheDir.resolve("anki-media/hoshi_audio.mp3").also { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        }
        filesDir.resolve("Dictionaries/Term/.dictionary-import-deadbeef/JMdict/index.json").also { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        }

        val report = StorageCleanupRepository(filesDir, cacheDir).scan()

        assertEquals(
            listOf(
                StorageCleanupCategoryId.AnkiMediaCache,
                StorageCleanupCategoryId.EpubImportResidue,
                StorageCleanupCategoryId.BackupRestoreResidue,
                StorageCleanupCategoryId.DictionaryImportResidue,
                StorageCleanupCategoryId.LocalAudioImportResidue,
                StorageCleanupCategoryId.OrphanSasayakiAudio,
            ),
            report.categories.map { it.id },
        )
        assertEquals(35L, report.totalSizeBytes)
        assertEquals(7, report.totalItemCount)
    }

    @Test
    fun cleanDeletesOnlyScannedTargets() {
        val filesDir = temporaryFolder.newFolder("clean-files")
        val cacheDir = temporaryFolder.newFolder("clean-cache")
        val keeper = filesDir.resolve("Books/Book/Sasayaki/sasayaki_audio.m4b").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("keep")
        }
        filesDir.resolve("Books/Book/sasayaki_playback.json")
            .writeText("""{"lastPosition":0.0,"audioFileName":"sasayaki_audio.m4b"}""")
        val orphan = filesDir.resolve("Books/Book/Sasayaki/orphan.mp3").also { file ->
            file.writeText("delete")
        }
        val cache = cacheDir.resolve("anki-media/hoshi_audio.mp3").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("delete")
        }

        val repository = StorageCleanupRepository(filesDir, cacheDir)
        val report = repository.scan()
        repository.clean(report)

        assertTrue(keeper.exists())
        assertFalse(orphan.exists())
        assertFalse(cache.exists())
    }

    @Test
    fun scanCountsEmptyResidueDirectoriesAsCleanableItems() {
        val filesDir = temporaryFolder.newFolder("empty-files")
        val cacheDir = temporaryFolder.newFolder("empty-cache")
        filesDir.resolve("ImportTemp").mkdirs()

        val report = StorageCleanupRepository(filesDir, cacheDir).scan()

        assertTrue(report.hasCleanableItems)
        assertEquals(listOf(StorageCleanupCategoryId.EpubImportResidue), report.categories.map { it.id })
        assertEquals(0L, report.totalSizeBytes)
        assertEquals(1, report.totalItemCount)
    }

    @Test
    fun scanCleansOnlyCompletedDictionaryReplacementBackups() {
        val filesDir = temporaryFolder.newFolder("dictionary-replace-files")
        val cacheDir = temporaryFolder.newFolder("dictionary-replace-cache")
        val typeDirectory = filesDir.resolve("Dictionaries/Term")
        val completedBackup = typeDirectory.resolve(".Existing-replace-done/index.json").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("old")
        }
        val onlyBackup = typeDirectory.resolve(".Missing-replace-incomplete/index.json").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("only-copy")
        }
        typeDirectory.resolve("Existing/index.json").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("new")
        }

        val repository = StorageCleanupRepository(filesDir, cacheDir)
        repository.clean(repository.scan())

        assertFalse(completedBackup.exists())
        assertTrue(onlyBackup.exists())
    }

    @Test
    fun scanCleansOnlyCompletedRestoreBackups() {
        val filesDir = temporaryFolder.newFolder("restore-backup-files")
        val cacheDir = temporaryFolder.newFolder("restore-backup-cache")
        val completedBackup = filesDir.resolve(".books-restore-backup-done/old.txt").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("old")
        }
        val onlyBackup = filesDir.resolve(".dictionaries-restore-backup-incomplete/old.txt").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("only-copy")
        }
        filesDir.resolve("Books/new.txt").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("new")
        }

        val repository = StorageCleanupRepository(filesDir, cacheDir)
        repository.clean(repository.scan())

        assertFalse(completedBackup.exists())
        assertTrue(onlyBackup.exists())
    }
}
