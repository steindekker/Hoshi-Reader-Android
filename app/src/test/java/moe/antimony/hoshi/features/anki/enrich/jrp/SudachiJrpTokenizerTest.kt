package moe.antimony.hoshi.features.anki.enrich.jrp

import java.io.File
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * [SudachiJrpTokenizer] against SudachiPy 0.6.11 + sudachidict_full 20260428 output, on cases where
 * plain Java Sudachi disagrees with sudachi.rs. Needs the real dictionary: set `HOSHI_SUDACHI_DIC`
 * (env var or system property) to its `system.dic`; skipped otherwise.
 */
class SudachiJrpTokenizerTest {

    private fun row(text: String): List<String> = tokenizer!!.tokenizeC(text).map { m ->
        val pos = m.partOfSpeech()
        "${m.surface()}|${pos[0]},${pos[1]},${pos[2]}|${m.dictionaryForm()}|${m.readingForm()}|" +
            m.splitA().joinToString("+") { it.surface() }
    }

    @Test
    fun katakanaRunsJoinIntoANewOovNode() {
        assertEquals(
            listOf(
                "マキナ|名詞,普通名詞,一般|マキナ|マキナ|",
                "に|助詞,格助詞,*|に|ニ|",
                "目配せ|名詞,普通名詞,サ変可能|目配せ|メクバセ|",
                "し|動詞,非自立可能,*|する|シ|",
                "た|助動詞,*,*|た|タ|",
                "。|補助記号,句点,*|。|。|",
            ),
            row("マキナに目配せした。"),
        )
    }

    @Test
    fun noDictionaryWordEndsBeforeAProlongedSoundMark() {
        assertEquals(
            listOf(
                "じ|名詞,普通名詞,一般|じ|じ|",
                "ー|補助記号,一般,*|ー|ー|",
                "っ|補助記号,一般,*|っ|ッ|",
                "、|補助記号,読点,*|、|、|",
                "と|助詞,格助詞,*|と|ト|",
            ),
            row("じーっ、と"),
        )
    }

    @Test
    fun joinedNumbersConcatenateRawReadings() {
        assertEquals(
            listOf(
                "余剰|名詞,普通名詞,一般|余剰|ヨジョウ|",
                "カロリー|名詞,普通名詞,助数詞可能|カロリー|カロリー|",
                "は|助詞,係助詞,*|は|ハ|",
                "7,000|名詞,数詞,*|7,000|ナナレイレイレイ|",
                "kcal|名詞,普通名詞,助数詞可能|kcal|キロカロリー|",
                "。|補助記号,句点,*|。|。|",
            ),
            row("余剰カロリーは7,000kcal。"),
        )
    }

    @Test
    fun normalizationUsesFullLowercasingAndYomiganaIsIgnored() {
        assertEquals(
            listOf("İstanbul|名詞,普通名詞,一般|i̇stanbul|i̇stanbul|", "へ|助詞,格助詞,*|へ|ヘ|"),
            row("İstanbulへ"),
        )
        assertEquals(
            listOf(
                "漢字(かんじ)|名詞,普通名詞,一般|漢字|カンジ|",
                "を|助詞,格助詞,*|を|ヲ|",
                "書く|動詞,一般,*|書く|カク|",
            ),
            row("漢字(かんじ)を書く"),
        )
    }

    @Test
    fun modeASplitsAndEmptyInput() {
        assertEquals(
            listOf("選挙管理委員会|名詞,固有名詞,一般|選挙管理委員会|センキョカンリイインカイ|選挙+管理+委員+会"),
            row("選挙管理委員会"),
        )
        assertEquals(emptyList<String>(), row(""))
    }

    companion object {
        private var tokenizer: SudachiJrpTokenizer? = null

        @BeforeClass
        @JvmStatic
        fun open() {
            val path = System.getProperty("HOSHI_SUDACHI_DIC") ?: System.getenv("HOSHI_SUDACHI_DIC")
            assumeTrue("HOSHI_SUDACHI_DIC not set", path != null && File(path).isFile)
            tokenizer = SudachiJrpTokenizer.open(File(path!!))
        }

        @AfterClass
        @JvmStatic
        fun close() {
            tokenizer?.close()
            tokenizer = null
        }
    }
}
