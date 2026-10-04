package moe.antimony.hoshi.features.texthooker

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextHookerSettingsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun defaultsTargetSteamDeckMagicDnsName() = withRepository { repository ->
        val settings = repository.settings.first()
        assertEquals("steamdeck", settings.host)
        assertEquals(7277, settings.port)
        assertEquals("", settings.token)
        assertEquals("http://steamdeck:7277/api/status", settings.endpointOrNull()?.url("/api/status").toString())
    }

    @Test
    fun persistsTrimmedValues() = withRepository { repository ->
        repository.update { it.copy(host = " deck.tail1234.ts.net ", port = 8080, token = " secret ", screenshotMaxWidth = 0) }
        val settings = repository.settings.first()
        assertEquals("deck.tail1234.ts.net", settings.host)
        assertEquals(8080, settings.port)
        assertEquals("secret", settings.token)
        assertEquals(0, settings.screenshotMaxWidth)
    }

    @Test
    fun blankHostMeansNotConfigured() = withRepository { repository ->
        repository.update { it.copy(host = "") }
        val settings = repository.settings.first()
        assertFalse(settings.isConfigured)
        assertNull(settings.endpointOrNull())
    }

    @Test
    fun validatesHostsPortsAndTokens() {
        listOf("steamdeck", "deck.tail1234.ts.net", "100.101.102.103", "fd7a:115c:a1e0::1", "[fd7a:115c:a1e0::1]", "")
            .forEach { assertNull(it, validateTextHookerHost(it)) }
        listOf("steam deck", "http://deck/x", "deck:7277", "user@deck", "deck/path", "deck?x")
            .forEach { assertEquals(it, TextHookerSettingsError.HostInvalid, validateTextHookerHost(it)) }
        assertNull(validateTextHookerPort("7277"))
        listOf("", "0", "65536", "abc").forEach {
            assertEquals(TextHookerSettingsError.PortInvalid, validateTextHookerPort(it))
        }
        assertNull(validateTextHookerToken("abc-123"))
        assertEquals(TextHookerSettingsError.TokenInvalid, validateTextHookerToken("a\nb"))
    }

    @Test
    fun normalizesPastedUrlsAndIpv6Literals() {
        assertEquals("steamdeck", normalizeTextHookerHost(" http://steamdeck/ "))
        assertEquals("fd7a::1", normalizeTextHookerHost("[fd7a::1]"))
        assertEquals("[fd7a::1]:7277", TextHookerSettings(host = "fd7a::1").displayAddress)
        assertEquals("http://[fd7a::1]:7277/api/ws?after=5", TextHookerSettings(host = "fd7a::1").endpointOrNull()
            ?.url("/api/ws", mapOf("after" to "5")).toString())
    }

    @Test
    fun settingsFormRejectsInvalidInputAndDetectsChanges() {
        val saved = TextHookerSettings()
        val unchanged = TextHookerSettingsUiState(loaded = true, saved = saved, form = saved.toForm())
        assertFalse(unchanged.hasChanges)
        val invalid = unchanged.copy(form = unchanged.form.copy(port = "70000"))
        assertEquals(TextHookerSettingsError.PortInvalid, invalid.portError)
        assertNull(invalid.formSettings())
        val pasted = unchanged.copy(form = unchanged.form.copy(host = "http://deck/"))
        assertEquals("deck", pasted.formSettings()?.host)
    }

    private fun withRepository(block: suspend (TextHookerSettingsRepository) -> Unit) = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
                File(tempFolder.root, "texthooker.preferences_pb")
            }
            block(TextHookerSettingsRepository(dataStore))
        } finally {
            scope.cancel()
        }
    }
}
