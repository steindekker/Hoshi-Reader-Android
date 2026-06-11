package moe.antimony.hoshi.features.anki

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.profiles.ProfileRepository

interface AnkiSettingsRepository {
    val settings: Flow<AnkiSettings>
    suspend fun update(transform: (AnkiSettings) -> AnkiSettings)
}

class DataStoreAnkiSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val profileRepository: ProfileRepository? = null,
) : AnkiSettingsRepository {
    private val profileSettingsVersion = MutableStateFlow(0)

    override val settings: Flow<AnkiSettings> =
        if (profileRepository == null) {
            dataStore.data.map { preferences -> preferences.toAnkiSettings() }
        } else {
            combine(dataStore.data, profileRepository.state, profileSettingsVersion) { preferences, _, _ ->
                profileSettingsOrMigrate(preferences)
            }
        }

    override suspend fun update(transform: (AnkiSettings) -> AnkiSettings) {
        if (profileRepository == null) {
            dataStore.edit { preferences ->
                val current = preferences.toAnkiSettings()
                preferences[KEY_SETTINGS] = json.encodeToString(transform(current))
            }
        } else {
            dataStore.edit { preferences ->
                val current = profileSettingsOrMigrate(preferences)
                saveProfileSettings(transform(current))
                profileSettingsVersion.value += 1
            }
        }
    }

    private fun profileSettingsOrMigrate(preferences: Preferences): AnkiSettings {
        val repository = profileRepository ?: return preferences.toAnkiSettings()
        val file = repository.ankiConfigFile()
        if (file.isFile) {
            return runCatching { json.decodeFromString<AnkiSettings>(file.readText()) }.getOrDefault(AnkiSettings())
        }
        val migrated = preferences.toAnkiSettings()
        saveProfileSettings(migrated)
        return migrated
    }

    private fun saveProfileSettings(settings: AnkiSettings) {
        val file = profileRepository?.ankiConfigFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(settings))
    }

    private fun Preferences.toAnkiSettings(): AnkiSettings =
        this[KEY_SETTINGS]?.let { raw ->
            runCatching { json.decodeFromString<AnkiSettings>(raw) }.getOrNull()
        } ?: AnkiSettings()

    private companion object {
        val KEY_SETTINGS = stringPreferencesKey("ankiSettings")
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

private val Context.ankiDataStore by preferencesDataStore(name = "anki_settings")

fun Context.ankiSettingsRepository(profileRepository: ProfileRepository? = null): AnkiSettingsRepository =
    DataStoreAnkiSettingsRepository(
        dataStore = ankiDataStore,
        profileRepository = profileRepository,
    )
