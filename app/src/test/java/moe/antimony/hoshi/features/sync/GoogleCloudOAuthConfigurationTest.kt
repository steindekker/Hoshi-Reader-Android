package moe.antimony.hoshi.features.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCloudOAuthConfigurationTest {
    @Test
    fun configurationExplainsDeviceCodeProjectSetup() {
        val configuration = GoogleCloudOAuthConfiguration
        val steps = configuration.instructions

        assertEquals(
            "https://github.com/ttu-ttu/ebook-reader?tab=readme-ov-file#storage-sources",
            configuration.ttuSetupUrl,
        )
        assertTrue(configuration.introduction.contains("Device Code flow"))
        assertTrue(configuration.introduction.contains("same user-owned Google Cloud project"))
        assertEquals(6, steps.size)
        assertEquals("ッツ Google Cloud setup", configuration.ttuSetupLinkLabel)
        assertEquals("Google Cloud Console", configuration.googleCloudConsoleLinkLabel)
        assertEquals("https://console.cloud.google.com/auth/clients", configuration.googleCloudConsoleUrl)
        assertEquals("https://www.google.com/device", configuration.googleDeviceUrl)
        assertEquals("Google device page", configuration.googleDeviceLinkLabel)
        assertTrue(steps.any { it.contains("same Google Cloud project") && it.contains("Google Drive API") })
        assertTrue(
            steps.any {
                it.contains(configuration.googleCloudConsoleLinkLabel) &&
                    it.contains("Google Auth Platform") &&
                    it.contains("Clients") &&
                    it.contains("CREATE CLIENT") &&
                    it.contains("TVs and Limited Input devices")
            },
        )
        assertTrue(steps.any { it.contains("client ID") && it.contains("client secret") })
        assertTrue(steps.any { it.contains("Do not create an Android OAuth client") })
        assertTrue(steps.any { it.contains("another phone or computer") && it.contains(configuration.googleDeviceLinkLabel) })
        assertFalse(steps.any { it.contains(configuration.googleDeviceUrl) })
        assertFalse(steps.any { it.contains(configuration.googleCloudConsoleUrl) })
        assertEquals(
            mapOf(
                configuration.googleCloudConsoleLinkLabel to configuration.googleCloudConsoleUrl,
                configuration.googleDeviceLinkLabel to configuration.googleDeviceUrl,
            ),
            configuration.instructionLinks,
        )
    }
}
