package moe.antimony.hoshi.features.profiles

import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.profiles.HoshiProfile
import moe.antimony.hoshi.profiles.ProfileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSettingsLayoutTest {
    @Test
    fun profileLanguageGroupsFollowSupportedLanguageOrderAndResolveDefaults() {
        val japaneseDefault = HoshiProfile(
            id = "default-ja",
            name = "Japanese",
            dictionaryLanguageId = ContentLanguageProfile.JapaneseLanguageId,
            isDefault = true,
        )
        val japaneseMining = HoshiProfile(
            id = "mining-ja",
            name = "Japanese mining",
            dictionaryLanguageId = ContentLanguageProfile.JapaneseLanguageId,
        )
        val english = HoshiProfile(
            id = "reading-en",
            name = "English reading",
            dictionaryLanguageId = ContentLanguageProfile.EnglishLanguageId,
        )
        val state = ProfileState(
            profiles = listOf(english, japaneseDefault, japaneseMining),
            defaultProfileId = japaneseDefault.id,
            globalActiveProfileId = english.id,
            loadedProfileId = null,
            primaryProfileIdsByLanguage = mapOf(
                ContentLanguageProfile.JapaneseLanguageId to japaneseMining.id,
                ContentLanguageProfile.EnglishLanguageId to english.id,
            ),
        )

        val groups = state.profileLanguageGroups()

        assertEquals(
            listOf(
                ContentLanguageProfile.JapaneseLanguageId,
                ContentLanguageProfile.EnglishLanguageId,
            ),
            groups.map { it.language.dictionaryLanguageId },
        )
        assertEquals(listOf(japaneseDefault, japaneseMining), groups[0].profiles)
        assertEquals(japaneseMining.id, groups[0].defaultProfileId)
        assertTrue(groups[0].canChooseDefault)
        assertEquals(listOf(english), groups[1].profiles)
        assertEquals(english.id, groups[1].defaultProfileId)
        assertFalse(groups[1].canChooseDefault)
    }
}
