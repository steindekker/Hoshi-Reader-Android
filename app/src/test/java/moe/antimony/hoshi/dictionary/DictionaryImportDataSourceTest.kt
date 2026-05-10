package moe.antimony.hoshi.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class DictionaryImportDataSourceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun importWritesToStagingThenCommitsImportedDictionaryToTypeDirectory() {
        val typeDirectory = temporaryFolder.newFolder("Term")
        val bridge = StagingDictionaryBridge("JMdict")
        val dataSource = DictionaryImportDataSource(bridge)

        dataSource.importDictionary(ByteArrayInputStream(byteArrayOf(1, 2, 3)), typeDirectory)

        assertEquals(1, bridge.outputDirs.size)
        assertTrue(bridge.outputDirs.single().startsWith(typeDirectory.resolve(".dictionary-import-").absolutePath))
        assertTrue(typeDirectory.resolve("JMdict/index.json").isFile)
        assertFalse(typeDirectory.listFiles().orEmpty().any { it.name.startsWith(".dictionary-import-") })
    }

    @Test
    fun failedImportRemovesStagingWithoutTouchingExistingDictionary() {
        val typeDirectory = temporaryFolder.newFolder("failure-Term")
        typeDirectory.resolve("Existing/index.json").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("keep")
        }
        val dataSource = DictionaryImportDataSource(FailingDictionaryBridge())

        try {
            dataSource.importDictionary(ByteArrayInputStream(byteArrayOf(1, 2, 3)), typeDirectory)
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Failed to import dictionary"))
        }

        assertEquals("keep", typeDirectory.resolve("Existing/index.json").readText())
        assertFalse(typeDirectory.listFiles().orEmpty().any { it.name.startsWith(".dictionary-import-") })
    }

    @Test
    fun importReplacesExistingDictionaryAfterStagingCommit() {
        val typeDirectory = temporaryFolder.newFolder("replace-Term")
        typeDirectory.resolve("Existing/index.json").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("old")
        }
        val dataSource = DictionaryImportDataSource(StagingDictionaryBridge("Existing"))

        dataSource.importDictionary(ByteArrayInputStream(byteArrayOf(1, 2, 3)), typeDirectory)

        assertEquals("""{"title":"Existing"}""", typeDirectory.resolve("Existing/index.json").readText())
        assertFalse(typeDirectory.listFiles().orEmpty().any { it.name.contains("-replace-") })
        assertFalse(typeDirectory.listFiles().orEmpty().any { it.name.startsWith(".dictionary-import-") })
    }

    private class StagingDictionaryBridge(
        private val dictionaryName: String,
    ) : DictionaryNativeBridge {
        val outputDirs = mutableListOf<String>()

        override fun importDictionary(zipPath: String, outputDir: String): Boolean {
            outputDirs += outputDir
            File(outputDir, "$dictionaryName/index.json").also { file ->
                file.parentFile!!.mkdirs()
                file.writeText("""{"title":"$dictionaryName"}""")
            }
            return true
        }

        override fun rebuildQuery(termPaths: Array<String>, freqPaths: Array<String>, pitchPaths: Array<String>) = Unit
    }

    private class FailingDictionaryBridge : DictionaryNativeBridge {
        override fun importDictionary(zipPath: String, outputDir: String): Boolean {
            File(outputDir, "Partial/index.json").also { file ->
                file.parentFile!!.mkdirs()
                file.writeText("""{"title":"Partial"}""")
            }
            return false
        }

        override fun rebuildQuery(termPaths: Array<String>, freqPaths: Array<String>, pitchPaths: Array<String>) = Unit
    }
}
