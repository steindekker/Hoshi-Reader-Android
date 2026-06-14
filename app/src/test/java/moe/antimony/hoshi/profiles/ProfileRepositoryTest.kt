package moe.antimony.hoshi.profiles

import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.epub.BookMetadata
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.testing.CountingCoroutineDispatcher
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
    fun profileMutationsUseInjectedIoDispatcher() = runBlocking {
        CountingCoroutineDispatcher().use { ioDispatcher ->
            val repository = ProfileRepository(
                filesDir = tempFolder.newFolder("files"),
                ioDispatcher = ioDispatcher,
            )
            val beforeMutations = ioDispatcher.dispatchCount

            val english = repository.createProfile("English", "en")
            repository.renameProfile(english.id, "English Mining")
            repository.setPrimaryProfile("en", english.id)
            repository.activateGlobal(english.id)
            repository.deleteProfile(english.id)

            assertTrue(ioDispatcher.dispatchCount >= beforeMutations + 5)
        }
    }

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
    fun createsActivatesAndSelectsPrimaryEnglishProfile() = runBlocking {
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
    fun firstProfileForLanguageBecomesPrimaryForAutomaticBookActivation() = runBlocking {
        val repository = ProfileRepository(tempFolder.newFolder("files"))

        val english = repository.createProfile("English mining", "en")

        assertEquals(english.id, repository.state.value.primaryProfileIdsByLanguage.getValue("en"))

        repository.activateForBook(bookMetadata(bookLanguage = "en-US"))

        assertEquals(english.id, repository.state.value.effectiveProfile.id)
        assertEquals(ContentLanguageProfile.English, repository.state.value.effectiveContentLanguageProfile)
    }

    @Test
    fun createProfileCopiesProfileOwnedFilesFromGlobalActiveProfile() = runBlocking {
        val repository = ProfileRepository(tempFolder.newFolder("files"))
        val sourceProfileId = repository.state.value.globalActiveProfileId
        val dictionaryConfig = """{"termDictionaries":[{"fileName":"b","isEnabled":true,"order":0}]}"""
        val dictionarySettings = """{"maxResults":12,"customCSS":".term{}"}"""
        val ankiConfig = """{"selectedDeckName":"Japanese"}"""
        val readerSettings = """{"theme":"Dark","fontSize":30}"""
        repository.dictionaryConfigFile(sourceProfileId).writeProfileText(dictionaryConfig)
        repository.dictionarySettingsFile(sourceProfileId).writeProfileText(dictionarySettings)
        repository.ankiConfigFile(sourceProfileId).writeProfileText(ankiConfig)
        repository.readerSettingsFile(sourceProfileId).writeProfileText(readerSettings)

        val english = repository.createProfile("English", "en")

        assertEquals(dictionaryConfig, repository.dictionaryConfigFile(english.id).readText())
        assertEquals(dictionarySettings, repository.dictionarySettingsFile(english.id).readText())
        assertEquals(ankiConfig, repository.ankiConfigFile(english.id).readText())
        assertEquals(readerSettings, repository.readerSettingsFile(english.id).readText())
    }

    @Test
    fun dictionaryBackupPayloadCopiesOnlyDictionaryProfileFilesAndNormalizedIndex() = runBlocking {
        val repository = ProfileRepository(tempFolder.newFolder("files"))
        val defaultId = repository.state.value.defaultProfileId
        val defaultConfig = """{"termDictionaries":[{"fileName":"JMdict","isEnabled":false,"order":0}]}"""
        val defaultSettings = """{"customCSS":".jp{}"}"""
        val english = repository.createProfile("English", "en")
        val englishConfig = """{"termDictionaries":[{"fileName":"Oxford","isEnabled":true,"order":0}]}"""
        val englishSettings = """{"customCSS":".en{}"}"""
        repository.dictionaryConfigFile(defaultId).writeProfileText(defaultConfig)
        repository.dictionarySettingsFile(defaultId).writeProfileText(defaultSettings)
        repository.ankiConfigFile(defaultId).writeProfileText("""{"selectedDeckName":"Japanese"}""")
        repository.readerSettingsFile(defaultId).writeProfileText("""{"theme":"Dark"}""")
        repository.dictionaryConfigFile(english.id).writeProfileText(englishConfig)
        repository.dictionarySettingsFile(english.id).writeProfileText(englishSettings)
        val payload = tempFolder.newFolder("payload")

        repository.writeDictionaryBackupProfilePayload(payload)

        val indexText = payload.resolve("profiles.json").readText()
        assertTrue(indexText.contains("default-ja"))
        assertTrue(indexText.contains(english.id))
        assertEquals(defaultConfig, payload.resolve("$defaultId/dictionary_config.json").readText())
        assertEquals(defaultSettings, payload.resolve("$defaultId/dictionary_settings.json").readText())
        assertEquals(englishConfig, payload.resolve("${english.id}/dictionary_config.json").readText())
        assertEquals(englishSettings, payload.resolve("${english.id}/dictionary_settings.json").readText())
        assertFalse(payload.resolve("$defaultId/anki_config.json").exists())
        assertFalse(payload.resolve("$defaultId/reader_settings.json").exists())
    }

    @Test
    fun prepareDictionaryBackupProfilesRestoreUsesPayloadAndRootConfigThenReloadPublishesState() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val repository = ProfileRepository(filesDir)
        val restoredDictionaries = tempFolder.newFolder("restoredDictionaries")
        val preparedProfiles = tempFolder.newFolder("preparedProfiles")
        val rootDefaultConfig = """{"termDictionaries":[{"fileName":"JMdict","isEnabled":false,"order":0}]}"""
        val payloadDefaultConfig = """{"termDictionaries":[{"fileName":"Payload","isEnabled":true,"order":0}]}"""
        val englishConfig = """{"termDictionaries":[{"fileName":"Oxford","isEnabled":true,"order":0}]}"""
        restoredDictionaries.resolve("config.json").writeProfileText(rootDefaultConfig)
        restoredDictionaries.resolve(".hoshi-profiles/profiles.json").writeProfileText(profilesJson(globalActiveProfileId = "profile-en"))
        restoredDictionaries.resolve(".hoshi-profiles/default-ja/dictionary_config.json").writeProfileText(payloadDefaultConfig)
        restoredDictionaries.resolve(".hoshi-profiles/profile-en/dictionary_config.json").writeProfileText(englishConfig)
        restoredDictionaries.resolve(".hoshi-profiles/profile-en/dictionary_settings.json").writeProfileText("""{"customCSS":".en{}"}""")

        repository.prepareDictionaryBackupProfilesRestore(
            restoredDictionariesDir = restoredDictionaries,
            destinationProfilesDir = preparedProfiles,
        )
        filesDir.resolve("Profiles").deleteRecursively()
        preparedProfiles.copyRecursively(filesDir.resolve("Profiles"))
        repository.reloadProfilesFromDisk()

        assertFalse(restoredDictionaries.resolve(".hoshi-profiles").exists())
        assertEquals("profile-en", repository.state.value.globalActiveProfileId)
        assertEquals(rootDefaultConfig, repository.dictionaryConfigFile("default-ja").readText())
        assertEquals(englishConfig, repository.dictionaryConfigFile("profile-en").readText())
        assertEquals("""{"customCSS":".en{}"}""", repository.dictionarySettingsFile("profile-en").readText())
    }

    @Test
    fun rejectsUnsupportedDictionaryLanguageIds() = runBlocking {
        val repository = ProfileRepository(tempFolder.newFolder("files"))

        try {
            repository.createProfile("French", "fr")
            fail("Unsupported dictionary language id should be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("Unsupported dictionary language"))
        }
    }

    @Test
    fun activateForBookUsesForcedProfileThenLanguagePrimaryThenGlobalFallback() = runBlocking {
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

    private fun java.io.File.writeProfileText(value: String) {
        parentFile?.mkdirs()
        writeText(value)
    }

    private fun profilesJson(globalActiveProfileId: String = "default-ja"): String =
        """
        {
          "profiles": [
            {"id": "default-ja", "name": "Japanese", "dictionaryLanguageId": "ja", "isDefault": true},
            {"id": "profile-en", "name": "English", "dictionaryLanguageId": "en", "isDefault": false}
          ],
          "defaultProfileId": "default-ja",
          "globalActiveProfileId": "$globalActiveProfileId",
          "primaryProfileIdsByLanguage": {"ja": "default-ja", "en": "profile-en"}
        }
        """.trimIndent()
}
