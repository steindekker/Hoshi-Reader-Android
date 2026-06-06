package moe.antimony.hoshi.navigation

import androidx.navigation3.runtime.NavKey
import moe.antimony.hoshi.features.dictionary.DictionarySettings

internal class AppLaunchRouteStateHolder {
    private var dictionaryDefaultRouteApplied = false

    fun defaultRouteAfterSettingsLoad(
        settings: DictionarySettings,
        hasPendingImport: Boolean,
        isBooksTabSelected: Boolean,
        backStack: List<NavKey>,
    ): AppRoute? {
        if (dictionaryDefaultRouteApplied) {
            return null
        }
        dictionaryDefaultRouteApplied = true
        return if (
            settings.dictionaryTabDefault &&
            !hasPendingImport &&
            isBooksTabSelected &&
            backStack.size == 1 &&
            backStack.lastOrNull() == AppRoute.BooksRoute
        ) {
            AppRoute.DictionaryRoute
        } else {
            null
        }
    }
}
