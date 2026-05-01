package moe.antimony.hoshi.features.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSettingsTest {
    @Test
    fun defaultSettingsMatchIosAudioDefaults() {
        val settings = AudioSettings()

        assertEquals(listOf(AudioSettings.DefaultAudioSource), settings.audioSources)
        assertFalse(settings.enableLocalAudio)
        assertFalse(settings.enableAutoplay)
        assertEquals(AudioPlaybackMode.Interrupt, settings.playbackMode)
        assertEquals(listOf(AudioSettings.DefaultAudioSource.url), settings.enabledAudioSourceUrls)
    }

    @Test
    fun enablingLocalAudioAddsLocalSourceAtFrontOnce() {
        val settings = AudioSettings().withLocalAudioEnabled(true)
            .withLocalAudioEnabled(true)

        assertTrue(settings.enableLocalAudio)
        assertEquals(AudioSettings.LocalAudioSource, settings.audioSources.first())
        assertEquals(1, settings.audioSources.count { it.url == AudioSettings.LocalAudioSource.url })
    }

    @Test
    fun disablingLocalAudioRemovesLocalSource() {
        val settings = AudioSettings().withLocalAudioEnabled(true)
            .withLocalAudioEnabled(false)

        assertFalse(settings.enableLocalAudio)
        assertFalse(settings.audioSources.any { it.url == AudioSettings.LocalAudioSource.url })
    }

    @Test
    fun addSourceIgnoresDuplicateUrlsLikeIos() {
        val settings = AudioSettings().addSource(
            AudioSource(
                name = "Default Copy",
                url = AudioSettings.DefaultAudioSource.url,
            ),
        )

        assertEquals(listOf(AudioSettings.DefaultAudioSource), settings.audioSources)
    }

}
