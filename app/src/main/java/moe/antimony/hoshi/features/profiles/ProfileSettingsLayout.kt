package moe.antimony.hoshi.features.profiles

import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.profiles.HoshiProfile
import moe.antimony.hoshi.profiles.ProfileState

internal data class ProfileLanguageGroup(
    val language: ContentLanguageProfile,
    val profiles: List<HoshiProfile>,
    val defaultProfileId: String,
    val canChooseDefault: Boolean,
)

internal fun ProfileState.profileLanguageGroups(): List<ProfileLanguageGroup> =
    ContentLanguageProfile.Supported.mapNotNull { language ->
        val languageProfiles = profiles.filter { profile ->
            profile.dictionaryLanguageId == language.dictionaryLanguageId
        }
        if (languageProfiles.isEmpty()) return@mapNotNull null
        val languageProfileIds = languageProfiles.mapTo(mutableSetOf()) { it.id }
        val defaultProfileId = primaryProfileIdsByLanguage[language.dictionaryLanguageId]
            ?.takeIf { it in languageProfileIds }
            ?: languageProfiles.first().id
        ProfileLanguageGroup(
            language = language,
            profiles = languageProfiles,
            defaultProfileId = defaultProfileId,
            canChooseDefault = languageProfiles.size > 1,
        )
    }
