package moe.antimony.hoshi.features.anki.enrich

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import moe.antimony.hoshi.features.anki.LocalExampleSentenceSource

@Module
@InstallIn(SingletonComponent::class)
internal interface AnkiEnrichModule {
    @Binds
    fun bindAnkiFieldEnricher(repository: EnrichPackRepository): AnkiFieldEnricher

    @Binds
    fun bindLocalExampleSentenceSource(source: PackExampleSentenceSource): LocalExampleSentenceSource

    @Binds
    fun bindEnrichPackSettingsRepository(repository: DataStoreEnrichPackSettingsRepository): EnrichPackSettingsRepository
}
