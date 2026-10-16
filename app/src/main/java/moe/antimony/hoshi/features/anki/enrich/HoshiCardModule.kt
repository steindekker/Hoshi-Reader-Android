package moe.antimony.hoshi.features.anki.enrich

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import hoshi.card.CardEnricher
import hoshi.card.HoshiCardEnricher
import hoshi.card.jrp.JrpSentenceModel
import hoshi.card.pack.EnrichmentPack
import hoshi.card.pack.EnrichmentPackFormat
import hoshi.card.pack.PackSettings
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import moe.antimony.hoshi.di.ApplicationScope
import moe.antimony.hoshi.di.FilesDir
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.features.anki.LocalExampleSentenceSource
import moe.antimony.hoshi.features.setup.hoshiSetupConnections

/** Hilt wiring for the hoshi-card library: one pack and sentence model under `files/enrich`. */
@Module
@InstallIn(SingletonComponent::class)
internal object HoshiCardModule {
    @Provides
    @Singleton
    fun sentenceModel(@FilesDir filesDir: File): JrpSentenceModel =
        JrpSentenceModel.inDirectory(File(filesDir, EnrichmentPackFormat.DIRECTORY))

    @Provides
    @Singleton
    fun enrichmentPack(
        @FilesDir filesDir: File,
        settings: DataStorePackSettings,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        sentenceModel: JrpSentenceModel,
    ): EnrichmentPack = EnrichmentPack(
        directory = File(filesDir, EnrichmentPackFormat.DIRECTORY),
        settings = settings,
        scope = scope,
        ioDispatcher = ioDispatcher,
        sentenceModel = sentenceModel,
        openConnection = hoshiSetupConnections()::open,
    )

    @Provides
    @Singleton
    fun cardEnricher(pack: EnrichmentPack): CardEnricher = HoshiCardEnricher(pack)
}

@Module
@InstallIn(SingletonComponent::class)
internal interface HoshiCardBindings {
    @Binds
    fun bindLocalExampleSentenceSource(source: PackExampleSentenceSource): LocalExampleSentenceSource

    @Binds
    fun bindPackSettings(settings: DataStorePackSettings): PackSettings
}

private val Context.enrichPackDataStore by preferencesDataStore(name = "enrich_pack")
private val ManifestUrlKey = stringPreferencesKey("manifest_url")

/** The pack's `pack.json` URL, set by first-run setup or in Settings → Anki. */
@Singleton
internal class DataStorePackSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) : PackSettings {
    override val manifestUrl: Flow<String> = context.enrichPackDataStore.data.map { it[ManifestUrlKey].orEmpty() }

    override suspend fun setManifestUrl(url: String) {
        context.enrichPackDataStore.edit { it[ManifestUrlKey] = url }
    }
}
