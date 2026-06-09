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
            "https://github.com/Manhhao/Hoshi-Reader/blob/main/TTUSYNC.md",
            configuration.ttuSetupUrl,
        )
        assertTrue(configuration.introduction.contains("Device Code flow"))
        assertTrue(configuration.introduction.contains("ッツ Sync guide"))
        assertEquals(6, steps.size)
        assertEquals("ッツ Sync guide", configuration.ttuSetupLinkLabel)
        assertEquals("Google Cloud Console", configuration.googleCloudConsoleLinkLabel)
        assertEquals("https://console.cloud.google.com/auth/clients", configuration.googleCloudConsoleUrl)
        assertEquals("https://www.google.com/device", configuration.googleDeviceUrl)
        assertEquals("Google device page", configuration.googleDeviceLinkLabel)
        assertTrue(steps.any { it.contains("ッツ Sync guide") && it.contains("Google Drive API") })
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
        assertTrue(steps.any { it.contains("standard Android OAuth client") })
        assertTrue(steps.any { it.contains("verification URL") && it.contains(configuration.googleDeviceLinkLabel) })
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
