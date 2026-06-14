package moe.antimony.hoshi.features.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.antimony.hoshi.profiles.ProfileRepository
import moe.antimony.hoshi.profiles.ProfileState

@HiltViewModel
internal class ProfilesViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {
    val profileState: StateFlow<ProfileState> = profileRepository.state

    fun createProfile(name: String, dictionaryLanguageId: String) {
        viewModelScope.launch {
            profileRepository.createProfile(name, dictionaryLanguageId)
        }
    }

    fun renameProfile(profileId: String, name: String) {
        viewModelScope.launch {
            profileRepository.renameProfile(profileId, name)
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            profileRepository.deleteProfile(profileId)
        }
    }

    fun activateGlobal(profileId: String) {
        viewModelScope.launch {
            profileRepository.activateGlobal(profileId)
        }
    }

    fun setPrimaryProfile(dictionaryLanguageId: String, profileId: String) {
        viewModelScope.launch {
            profileRepository.setPrimaryProfile(dictionaryLanguageId, profileId)
        }
    }
}
