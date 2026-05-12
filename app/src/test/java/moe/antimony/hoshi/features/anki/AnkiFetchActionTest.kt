package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiFetchActionTest {
    @Test
    fun unavailableAnkiDroidShowsUnavailableBeforeRequestingPermission() {
        assertEquals(
            AnkiFetchAction.ShowApiUnavailable,
            ankiFetchAction(isAnkiDroidAvailable = false, permissionGranted = false),
        )
    }

    @Test
    fun availableAnkiDroidRequestsPermissionWhenMissing() {
        assertEquals(
            AnkiFetchAction.RequestPermission,
            ankiFetchAction(isAnkiDroidAvailable = true, permissionGranted = false),
        )
    }

    @Test
    fun grantedPermissionFetchesConfiguration() {
        assertEquals(
            AnkiFetchAction.FetchConfiguration,
            ankiFetchAction(isAnkiDroidAvailable = true, permissionGranted = true),
        )
    }

    @Test
    fun appSettingsIntentUsesPackageUriForThisApp() {
        assertEquals(
            "package:moe.antimony.hoshi.debug",
            ankiPermissionSettingsUri("moe.antimony.hoshi.debug"),
        )
    }
}
