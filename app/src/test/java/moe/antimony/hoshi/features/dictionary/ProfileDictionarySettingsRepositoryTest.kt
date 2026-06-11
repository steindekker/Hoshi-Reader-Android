package moe.antimony.hoshi.features.dictionary

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.profiles.ProfileRepository
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileDictionarySettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun onlyCollapsedDictionariesAreScopedToActiveProfile() = runBlocking {
        val profileRepository = ProfileRepository(tempFolder.newFolder("files"))
        val repository = repository(profileRepository)

        repository.update { it.copy(maxResults = 12, collapsedDictionaries = setOf("JMdict")) }
        val english = profileRepository.createProfile("English", "en")
        profileRepository.activateGlobal(english.id)

        assertEquals(12, repository.settings.first().maxResults)
        assertEquals(emptySet<String>(), repository.settings.first().collapsedDictionaries)

        repository.update { it.copy(maxResults = 9, collapsedDictionaries = setOf("Oxford")) }
        profileRepository.activateGlobal(profileRepository.state.value.defaultProfileId)

        val japaneseSettings = repository.settings.first()
        assertEquals(9, japaneseSettings.maxResults)
        assertEquals(setOf("JMdict"), japaneseSettings.collapsedDictionaries)
    }

    private fun repository(profileRepository: ProfileRepository): DictionarySettingsRepository {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("dictionary-settings.preferences_pb") },
        )
        return DictionarySettingsRepository(
            dataStore = dataStore,
            profileRepository = profileRepository,
        ).also {
            tempFolder.root.deleteOnExit()
            Runtime.getRuntime().addShutdownHook(Thread { scope.cancel() })
        }
    }
}
