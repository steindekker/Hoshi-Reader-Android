package moe.antimony.hoshi.features.anki

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

class ProfileAnkiSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun settingsArePersistedPerActiveProfile() = runBlocking {
        val profileRepository = ProfileRepository(tempFolder.newFolder("files"))
        val repository = repository(profileRepository)

        repository.update { it.copy(selectedDeckName = "Japanese") }
        val english = profileRepository.createProfile("English", "en")
        profileRepository.activateGlobal(english.id)

        assertEquals(null, repository.settings.first().selectedDeckName)

        repository.update { it.copy(selectedDeckName = "English") }
        profileRepository.activateGlobal(profileRepository.state.value.defaultProfileId)

        assertEquals("Japanese", repository.settings.first().selectedDeckName)
        profileRepository.activateGlobal(english.id)
        assertEquals("English", repository.settings.first().selectedDeckName)
    }

    private fun repository(profileRepository: ProfileRepository): AnkiSettingsRepository {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("anki-settings.preferences_pb") },
        )
        return DataStoreAnkiSettingsRepository(
            dataStore = dataStore,
            profileRepository = profileRepository,
        ).also {
            Runtime.getRuntime().addShutdownHook(Thread { scope.cancel() })
        }
    }
}
