package moe.antimony.hoshi.profiles

import javax.inject.Inject
import javax.inject.Singleton
import moe.antimony.hoshi.epub.BookMetadata

@Singleton
class ProfileActivationService @Inject constructor(
    private val profileRepository: ProfileRepository,
) {
    fun activateForBook(metadata: BookMetadata): HoshiProfile =
        profileRepository.activateForBook(metadata)

    suspend fun activateGlobal(profileId: String) {
        profileRepository.activateGlobal(profileId)
    }

    fun clearLoadedProfile() {
        profileRepository.clearLoadedProfile()
    }
}
