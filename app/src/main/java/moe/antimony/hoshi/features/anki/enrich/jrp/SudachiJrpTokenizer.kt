package moe.antimony.hoshi.features.anki.enrich.jrp

import com.worksap.nlp.sudachi.Config
import com.worksap.nlp.sudachi.DictionaryFactory
import com.worksap.nlp.sudachi.HoshiRustCharCategory
import com.worksap.nlp.sudachi.HoshiRustCompatAnalyzer
import com.worksap.nlp.sudachi.HoshiRustDefaultInputText
import com.worksap.nlp.sudachi.JapaneseDictionary
import com.worksap.nlp.sudachi.Morpheme
import com.worksap.nlp.sudachi.PathAnchor
import com.worksap.nlp.sudachi.Tokenizer
import com.worksap.nlp.sudachi.dictionary.CharacterCategory
import java.io.Closeable
import java.io.File

/**
 * [JrpTokenizer] over Java Sudachi with a SudachiDict system dictionary, producing the same morphemes
 * as SudachiPy 0.6.11 (`Dictionary(dict="full").create()`, mode C) for the same dictionary build.
 *
 * Only `system.dic` comes from outside the app. char.def, unk.def and rewrite.def are SudachiPy's own
 * copies, bundled as Java resources next to this class; analysis follows sudachi.rs through
 * [HoshiRustCompatAnalyzer].
 *
 * Calls are serialized; morphemes stay readable until [close].
 */
internal class SudachiJrpTokenizer private constructor(
    private val dictionary: JapaneseDictionary,
    private val analyzer: HoshiRustCompatAnalyzer,
) : JrpTokenizer, Closeable {

    @Synchronized
    override fun tokenizeC(text: String): List<JrpMorpheme> =
        analyzer.analyze(text).map { SudachiMorpheme(it, this) }

    @Synchronized
    override fun close() {
        dictionary.close()
    }

    private class SudachiMorpheme(private val m: Morpheme, private val owner: SudachiJrpTokenizer) : JrpMorpheme {
        override fun surface(): String = m.surface()
        override fun partOfSpeech(): List<String> = m.partOfSpeech()
        override fun dictionaryForm(): String = m.dictionaryForm()

        // sudachi.rs answers an OOV's empty reading with its (normalized) surface, which is what
        // Java keeps as the dictionary form; Java returns "".
        override fun readingForm(): String = m.readingForm().ifEmpty { m.dictionaryForm() }

        // SudachiPy returns [] for an unsplittable morpheme and Java returns [itself]; callers only
        // look at splits with more than one part.
        override fun splitA(): List<JrpMorpheme> = synchronized(owner) {
            val parts = m.split(Tokenizer.SplitMode.A)
            if (parts.size <= 1) emptyList() else parts.map { SudachiMorpheme(it, owner) }
        }
    }

    companion object {
        private const val RESOURCE_DIR = "sudachi"

        /**
         * SudachiPy 0.6.11's default sudachi.json minus DefaultInputTextPlugin and the path rewriters,
         * which the analyzer runs with sudachi.rs semantics.
         */
        private val CONFIG = """
            {
              "inputTextPlugin": [
                { "class": "com.worksap.nlp.sudachi.ProlongedSoundMarkInputTextPlugin",
                  "prolongedSoundMarks": ["ー", "-", "⁓", "〜", "〰"],
                  "replacementSymbol": "ー" },
                { "class": "com.worksap.nlp.sudachi.IgnoreYomiganaPlugin",
                  "leftBrackets": ["(", "（"],
                  "rightBrackets": [")", "）"],
                  "maxYomiganaLength": 4 }
              ],
              "oovProviderPlugin": [
                { "class": "com.worksap.nlp.sudachi.MeCabOovProviderPlugin",
                  "charDef": "$RESOURCE_DIR/char.def",
                  "unkDef": "$RESOURCE_DIR/unk.def" },
                { "class": "com.worksap.nlp.sudachi.SimpleOovProviderPlugin",
                  "oovPOS": ["補助記号", "一般", "*", "*", "*", "*"],
                  "leftId": 5968,
                  "rightId": 5968,
                  "cost": 3857 }
              ],
              "pathRewritePlugin": []
            }
        """.trimIndent()

        /**
         * Opens [systemDic] (memory-mapped, read-only). Blocking; call off the main thread. The file
         * must stay in place until [close].
         */
        fun open(systemDic: File): SudachiJrpTokenizer {
            require(systemDic.isFile) { "missing Sudachi dictionary: $systemDic" }
            val charDef = resourceText("char.def")
            val javaCategories = CharacterCategory().apply {
                readCharacterDefinition(HoshiRustCharCategory.javaCompatible(charDef).byteInputStream())
            }
            val config = Config.fromJsonString(CONFIG, PathAnchor.classpath(SudachiJrpTokenizer::class.java))
                .systemDictionary(systemDic.toPath())
                .characterDefinition(javaCategories)
            @Suppress("DEPRECATION")
            val dictionary = DictionaryFactory().create(config) as JapaneseDictionary
            return try {
                SudachiJrpTokenizer(
                    dictionary,
                    HoshiRustCompatAnalyzer(
                        dictionary,
                        HoshiRustCharCategory.parse(charDef),
                        HoshiRustDefaultInputText(resourceText("rewrite.def")),
                    ),
                )
            } catch (e: Throwable) {
                dictionary.close()
                throw e
            }
        }

        private fun resourceText(name: String): String {
            val stream = SudachiJrpTokenizer::class.java.getResourceAsStream("$RESOURCE_DIR/$name")
                ?: error("missing bundled Sudachi resource $name")
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }
}
