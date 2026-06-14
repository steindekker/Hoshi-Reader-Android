package moe.antimony.hoshi.features.anki

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AnkiSettingsRepository {
    private val profileSettingsVersion = MutableStateFlow(0)
    private val profileSettingsLock = Mutex()

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
                profileSettingsLock.withLock {
                    val current = readProfileSettingsOrMigrate(preferences.toAnkiSettings())
                    saveProfileSettings(transform(current))
                }
                profileSettingsVersion.value += 1
            }
        }
    }

    private suspend fun profileSettingsOrMigrate(preferences: Preferences): AnkiSettings =
        profileSettingsLock.withLock {
            readProfileSettingsOrMigrate(preferences.toAnkiSettings())
        }

    private suspend fun readProfileSettingsOrMigrate(fallbackSettings: AnkiSettings): AnkiSettings = withContext(ioDispatcher) {
        val repository = profileRepository
        if (repository == null) {
            fallbackSettings
        } else {
            val file = repository.ankiConfigFile()
            if (file.isFile) {
                runCatching { json.decodeFromString<AnkiSettings>(file.readText()) }.getOrDefault(AnkiSettings())
            } else {
                val migrated = fallbackSettings
                saveProfileSettings(migrated)
                migrated
            }
        }
    }

    private suspend fun saveProfileSettings(settings: AnkiSettings) = withContext(ioDispatcher) {
        val file = profileRepository?.ankiConfigFile() ?: return@withContext
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

fun Context.ankiSettingsRepository(
    profileRepository: ProfileRepository? = null,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): AnkiSettingsRepository =
    DataStoreAnkiSettingsRepository(
        dataStore = ankiDataStore,
        profileRepository = profileRepository,
        ioDispatcher = ioDispatcher,
    )
