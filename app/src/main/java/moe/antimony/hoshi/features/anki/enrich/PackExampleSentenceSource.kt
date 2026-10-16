package moe.antimony.hoshi.features.anki.enrich

import hoshi.card.pack.EnrichmentPack
import javax.inject.Inject
import moe.antimony.hoshi.features.anki.ExampleSentence
import moe.antimony.hoshi.features.anki.LocalExampleSentenceSource

/** The pack's `{example-sentences}` for a term, read on device so the options sheet shows them at once. */
internal class PackExampleSentenceSource @Inject constructor(
    private val pack: EnrichmentPack,
) : LocalExampleSentenceSource {
    override fun candidates(term: String): List<ExampleSentence> =
        pack.exampleSentences(term).map { ExampleSentence(it.text, it.highlights) }
}
