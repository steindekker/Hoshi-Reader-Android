package moe.antimony.hoshi.features.live

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveSettingsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun defaultsToTheSteamDeckHost() = withRepository { repository, _ ->
        val settings = repository.settings.first()
        assertEquals(listOf("steamdeck"), settings.hosts.map { it.host })
        assertEquals(7277, settings.hosts.single().port)
        assertTrue(settings.autoPause)
        assertEquals(
            "http://steamdeck:7277/v2/status",
            settings.hosts.single().endpointOrNull()?.url("/v2/status").toString(),
        )
    }

    @Test
    fun persistsHostListTrimmedWithPreferences() = withRepository { repository, _ ->
        repository.update {
            it.copy(
                hosts = listOf(
                    LiveHost("a", " deck.tail1234.ts.net ", 8080, " secret "),
                    LiveHost("b", "pc", 7277),
                ),
                screenshotMaxWidth = 0,
                autoPause = false,
            )
        }
        val settings = repository.settings.first()
        assertEquals(listOf(LiveHost("a", "deck.tail1234.ts.net", 8080, "secret"), LiveHost("b", "pc", 7277)), settings.hosts)
        assertEquals(0, settings.screenshotMaxWidth)
        assertFalse(settings.autoPause)
        assertEquals("pc", settings.host("b")?.host)
    }

    @Test
    fun migratesTheVnTabsSingleHost() = withRepository { repository, dataStore ->
        dataStore.edit {
            it[stringPreferencesKey("textHookerHost")] = "100.64.0.7"
            it[intPreferencesKey("textHookerPort")] = 9000
            it[stringPreferencesKey("textHookerToken")] = "tok"
        }
        val migrated = repository.settings.first().hosts.single()
        assertEquals(LiveHost("default", "100.64.0.7", 9000, "tok"), migrated)
        repository.update { it.copy(hosts = it.hosts + LiveHost("pc", "pc")) }
        assertEquals(listOf("100.64.0.7", "pc"), repository.settings.first().hosts.map { it.host })
    }

    @Test
    fun aDisabledVnConnectionMigratesToNoHosts() = withRepository { repository, dataStore ->
        dataStore.edit { it[stringPreferencesKey("textHookerHost")] = "" }
        val settings = repository.settings.first()
        assertTrue(settings.hosts.isEmpty())
        assertFalse(settings.isConfigured)
    }

    @Test
    fun validatesHostsPortsAndTokens() {
        listOf("steamdeck", "deck.tail1234.ts.net", "100.101.102.103", "fd7a:115c:a1e0::1", "[fd7a:115c:a1e0::1]")
            .forEach { assertNull(it, validateLiveHost(it)) }
        listOf("", "steam deck", "http://deck/x", "deck:7277", "user@deck", "deck/path", "deck?x")
            .forEach { assertEquals(it, LiveSettingsError.HostInvalid, validateLiveHost(it)) }
        assertNull(validateLivePort("7277"))
        listOf("", "0", "65536", "abc").forEach { assertEquals(LiveSettingsError.PortInvalid, validateLivePort(it)) }
        assertNull(validateLiveToken("abc-123"))
        assertEquals(LiveSettingsError.TokenInvalid, validateLiveToken("a\nb"))
        assertNull(LiveHost("x", "deck x").endpointOrNull())
    }

    @Test
    fun normalizesPastedUrlsAndIpv6Literals() {
        assertEquals("steamdeck", normalizeLiveHost(" http://steamdeck/ "))
        assertEquals("fd7a::1", normalizeLiveHost("[fd7a::1]"))
        assertEquals("[fd7a::1]:7277", LiveHost("x", "fd7a::1").displayAddress)
        assertEquals("http://[fd7a::1]:7277/v2/ws", LiveHost("x", "fd7a::1").endpointOrNull()?.url("/v2/ws").toString())
    }

    @Test
    fun hostFormValidatesAndRejectsDuplicates() {
        val saved = LiveSettings(hosts = listOf(LiveHost("deck", "steamdeck", 7277)))
        val adding = LiveSettingsUiState(loaded = true, saved = saved, form = LiveHostForm(host = "http://pc/"))
        assertTrue(adding.isFormValid)
        assertEquals("pc", adding.formHost()?.host)
        val duplicate = adding.copy(form = LiveHostForm(host = "SteamDeck", port = "7277"))
        assertEquals(LiveSettingsError.HostDuplicate, duplicate.hostError)
        assertNull(duplicate.formHost())
        // Editing a host may keep its own address.
        val editing = adding.copy(form = LiveHostForm(editingId = "deck", host = "steamdeck", port = "7277"))
        assertNull(editing.hostError)
        assertEquals("deck", editing.formHost()?.id)
        assertEquals(LiveSettingsError.PortInvalid, adding.copy(form = LiveHostForm(host = "pc", port = "70000")).portError)
    }

    private fun withRepository(block: suspend (LiveSettingsRepository, DataStore<Preferences>) -> Unit) = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
                File(tempFolder.root, "live.preferences_pb")
            }
            block(LiveSettingsRepository(dataStore), dataStore)
        } finally {
            scope.cancel()
        }
    }
}
