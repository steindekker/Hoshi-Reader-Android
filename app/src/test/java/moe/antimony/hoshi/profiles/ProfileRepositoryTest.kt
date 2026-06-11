package moe.antimony.hoshi.profiles

import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.epub.BookMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun firstLaunchCreatesJapaneseDefaultProfileAndMigratesDictionaryConfig() {
        val filesDir = tempFolder.newFolder("files")
        val legacyConfig = filesDir.resolve("Dictionaries/config.json")
        requireNotNull(legacyConfig.parentFile).mkdirs()
        legacyConfig.writeText("""{"termDictionaries":[],"frequencyDictionaries":[],"pitchDictionaries":[]}""")

        val repository = ProfileRepository(filesDir)

        val state = repository.state.value
        assertEquals(ContentLanguageProfile.Japanese.dictionaryLanguageId, state.effectiveProfile.dictionaryLanguageId)
        assertEquals(state.defaultProfileId, state.globalActiveProfileId)
        assertEquals(state.defaultProfileId, state.primaryProfileIdsByLanguage.getValue("ja"))
        assertEquals("dictionary_config.json", repository.dictionaryConfigFile().name)
        assertTrue(repository.dictionaryConfigFile().isFile)
        assertFalse(legacyConfig.exists())
    }

    @Test
    fun createsActivatesAndSelectsPrimaryEnglishProfile() {
        val repository = ProfileRepository(tempFolder.newFolder("files"))

        val english = repository.createProfile("English mining", "en")
        repository.activateGlobal(english.id)
        repository.setPrimaryProfile("en", english.id)

        val state = repository.state.value
        assertEquals(english.id, state.globalActiveProfileId)
        assertEquals(english.id, state.effectiveProfile.id)
        assertEquals(english.id, state.primaryProfileIdsByLanguage.getValue("en"))
        assertEquals(ContentLanguageProfile.English, state.effectiveContentLanguageProfile)
    }

    @Test
    fun rejectsUnsupportedDictionaryLanguageIds() {
        val repository = ProfileRepository(tempFolder.newFolder("files"))

        try {
            repository.createProfile("French", "fr")
            fail("Unsupported dictionary language id should be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("Unsupported dictionary language"))
        }
    }

    @Test
    fun activateForBookUsesForcedProfileThenLanguagePrimaryThenGlobalFallback() {
        val repository = ProfileRepository(tempFolder.newFolder("files"))
        val english = repository.createProfile("English", "en")
        repository.setPrimaryProfile("en", english.id)

        repository.activateForBook(bookMetadata(bookLanguage = "en-US"))
        assertEquals(english.id, repository.state.value.effectiveProfile.id)

        repository.activateForBook(bookMetadata(profileId = repository.state.value.defaultProfileId, bookLanguage = "en-US"))
        assertEquals(repository.state.value.defaultProfileId, repository.state.value.effectiveProfile.id)

        repository.clearLoadedProfile()
        assertEquals(repository.state.value.globalActiveProfileId, repository.state.value.effectiveProfile.id)
    }

    private fun bookMetadata(profileId: String? = null, bookLanguage: String? = null): BookMetadata =
        BookMetadata(
            id = "book",
            title = "Book",
            cover = null,
            folder = "book",
            lastAccess = 0.0,
            profileId = profileId,
            bookLanguage = bookLanguage,
        )
}
