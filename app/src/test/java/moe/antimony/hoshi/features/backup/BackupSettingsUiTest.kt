package moe.antimony.hoshi.features.backup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupSettingsUiTest {
    @Test
    fun advancedSettingsIncludesIosStyleBackupEntry() {
        val source = File("src/main/java/moe/antimony/hoshi/features/audio/AudioView.kt").readText()
        val advancedSettings = source.substringAfter("fun AdvancedSettingsView(")
            .substringBefore("@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nfun AudioSettingsView(")

        assertTrue(advancedSettings.contains("AdvancedDestination.Backup"))
        assertTrue(advancedSettings.contains("BackupSettingsView("))
        assertTrue(advancedSettings.contains("Text(\"Backup\")"))
    }

    @Test
    fun backupSettingsIncludesIosBooksAndDictionariesSections() {
        val source = File("src/main/java/moe/antimony/hoshi/features/backup/BackupSettingsView.kt").readText()
        val backupSection = source.substringAfter("private fun BackupSection(")
            .substringBefore("@Composable\nprivate fun BackupGroupCard")

        assertTrue(source.contains("title = \"Books\""))
        assertTrue(source.contains("repository.exportBooks(context.contentResolver, uri)"))
        assertTrue(source.contains("repository.restoreBooks(context.contentResolver, uri)"))
        assertTrue(source.contains("title = \"Dictionaries\""))
        assertTrue(source.contains("repository.exportDictionaries(context.contentResolver, uri)"))
        assertTrue(source.contains("repository.restoreDictionaries(context.contentResolver, uri)"))
        assertTrue(source.contains("appContainer.dictionaryRepository.rebuildLookupQuery()"))
        assertTrue(source.contains("footer = \"Restoring will overwrite the current collection.\""))
        assertTrue(backupSection.contains("footer?.let"))
        assertTrue(backupSection.contains("MaterialTheme.typography.bodySmall"))
        assertTrue(!backupSection.contains("supportingContent = restoreDescription"))
    }
}
