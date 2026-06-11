package moe.antimony.hoshi.features.profiles

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import moe.antimony.hoshi.profiles.ProfileRepository
import moe.antimony.hoshi.profiles.ProfileState

@HiltViewModel
internal class ProfilesViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {
    val profileState: StateFlow<ProfileState> = profileRepository.state

    fun createProfile(name: String, dictionaryLanguageId: String) {
        profileRepository.createProfile(name, dictionaryLanguageId)
    }

    fun renameProfile(profileId: String, name: String) {
        profileRepository.renameProfile(profileId, name)
    }

    fun deleteProfile(profileId: String) {
        profileRepository.deleteProfile(profileId)
    }

    fun activateGlobal(profileId: String) {
        profileRepository.activateGlobal(profileId)
    }

    fun setPrimaryProfile(dictionaryLanguageId: String, profileId: String) {
        profileRepository.setPrimaryProfile(dictionaryLanguageId, profileId)
    }
}
