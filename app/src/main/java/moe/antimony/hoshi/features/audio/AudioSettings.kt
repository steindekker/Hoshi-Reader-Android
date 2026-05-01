package moe.antimony.hoshi.features.audio

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class AudioSource(
    val name: String = "",
    val url: String,
    val isEnabled: Boolean = true,
    val isDefault: Boolean = false,
)

enum class AudioPlaybackMode(val rawValue: String, val displayName: String) {
    Interrupt("interrupt", "Interrupt"),
    Duck("duck", "Lower Volume"),
    Mix("mix", "Keep Volume");

    companion object {
        fun fromRawValue(value: String?): AudioPlaybackMode =
            entries.firstOrNull { it.rawValue == value } ?: Interrupt
    }
}

data class AudioSettings(
    val audioSources: List<AudioSource> = listOf(DefaultAudioSource),
    val enableLocalAudio: Boolean = false,
    val enableAutoplay: Boolean = false,
    val playbackMode: AudioPlaybackMode = AudioPlaybackMode.Interrupt,
) {
    val enabledAudioSourceUrls: List<String>
        get() = audioSources.filter { it.isEnabled }.map { it.url }

    fun withLocalAudioEnabled(enabled: Boolean): AudioSettings {
        val withoutLocal = audioSources.filterNot { it.url == LocalAudioSource.url }
        return copy(
            enableLocalAudio = enabled,
            audioSources = if (enabled) listOf(LocalAudioSource) + withoutLocal else withoutLocal,
        )
    }

    fun addSource(source: AudioSource): AudioSettings {
        if (source.url.isBlank() || source.name.isBlank()) return this
        if (audioSources.any { it.url == source.url }) return this
        return copy(audioSources = audioSources + source)
    }

    companion object {
        const val LocalAudioPath = "Audio/android.db"
        const val LocalAudioUrl = "http://localhost:8765/localaudio/get/?term={term}&reading={reading}"

        val LocalAudioSource = AudioSource(
            name = "Local",
            url = LocalAudioUrl,
            isEnabled = true,
        )

        val DefaultAudioSource = AudioSource(
            name = "Default",
            url = "https://hoshi-reader.manhhaoo-do.workers.dev/?term={term}&reading={reading}",
            isEnabled = true,
            isDefault = true,
        )
    }
}

class AudioSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("audio-settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): AudioSettings {
        val sources = preferences.getString(KEY_AUDIO_SOURCES, null)
            ?.let { encoded ->
                runCatching { json.decodeFromString(ListSerializer(AudioSource.serializer()), encoded) }.getOrNull()
            }
            ?: listOf(AudioSettings.DefaultAudioSource)
        return AudioSettings(
            audioSources = sources,
            enableLocalAudio = preferences.getBoolean(KEY_ENABLE_LOCAL_AUDIO, false),
            enableAutoplay = preferences.getBoolean(KEY_AUDIO_ENABLE_AUTOPLAY, false),
            playbackMode = AudioPlaybackMode.fromRawValue(preferences.getString(KEY_AUDIO_PLAYBACK_MODE, null)),
        ).let { settings ->
            if (settings.enableLocalAudio && settings.audioSources.none { it.url == AudioSettings.LocalAudioSource.url }) {
                settings.withLocalAudioEnabled(true)
            } else if (!settings.enableLocalAudio && settings.audioSources.any { it.url == AudioSettings.LocalAudioSource.url }) {
                settings.withLocalAudioEnabled(false)
            } else {
                settings
            }
        }
    }

    fun save(settings: AudioSettings) {
        preferences.edit()
            .putString(KEY_AUDIO_SOURCES, json.encodeToString(ListSerializer(AudioSource.serializer()), settings.audioSources))
            .putBoolean(KEY_ENABLE_LOCAL_AUDIO, settings.enableLocalAudio)
            .remove(KEY_LOCAL_AUDIO_DATABASE_URI)
            .putBoolean(KEY_AUDIO_ENABLE_AUTOPLAY, settings.enableAutoplay)
            .putString(KEY_AUDIO_PLAYBACK_MODE, settings.playbackMode.rawValue)
            .apply()
    }

    private companion object {
        const val KEY_AUDIO_SOURCES = "audioSources"
        const val KEY_ENABLE_LOCAL_AUDIO = "enableLocalAudio"
        const val KEY_LOCAL_AUDIO_DATABASE_URI = "localAudioDatabaseUri"
        const val KEY_AUDIO_ENABLE_AUTOPLAY = "audioEnableAutoplay"
        const val KEY_AUDIO_PLAYBACK_MODE = "audioPlaybackMode"
    }
}
