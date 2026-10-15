package moe.antimony.hoshi.features.anki.enrich.jrp

import org.junit.Assert.assertEquals
import org.junit.Test

/** Each merge path of [JrpTokenize] against fake Sudachi morphemes and a fake accent index. */
class JrpTokenizeTest {

    private class FakeMorpheme(
        private val surface: String,
        private val pos: List<String>,
        private val dictionaryForm: String,
        private val reading: String,
        private val split: List<JrpMorpheme> = emptyList(),
    ) : JrpMorpheme {
        override fun surface() = surface
        override fun partOfSpeech() = pos
        override fun dictionaryForm() = dictionaryForm
        override fun readingForm() = reading
        override fun splitA() = split
    }

    private fun m(
        surface: String,
        pos0: String,
        reading: String,
        dict: String = surface,
        pos1: String = "*",
        split: List<JrpMorpheme> = emptyList(),
    ): JrpMorpheme = FakeMorpheme(surface, listOf(pos0, pos1, "*", "*", "*", "*"), dict, reading, split)

    /** Tokenizes registered texts only, so every isolated lemma re-tokenization is explicit. */
    private class FakeTokenizer(private val texts: Map<String, List<JrpMorpheme>>) : JrpTokenizer {
        override fun tokenizeC(text: String): List<JrpMorpheme> = texts[text] ?: error("unexpected tokenize: $text")
    }

    /** Same semantics as `accent.py` over a `lemma\treading -> positions` table. */
    private class FakeAccentIndex(vararg entries: Pair<String, List<Int>>) : JrpAccentIndex {
        private val table = linkedMapOf(*entries)

        override fun lookup(lemma: String, readingHira: String): List<Int>? = table["$lemma\t$readingHira"]

        override fun readingsFor(lemma: String): Map<String, List<Int>> {
            val out = LinkedHashMap<String, List<Int>>()
            for ((key, positions) in table) {
                val (lem, rd) = key.split("\t", limit = 2)
                if (lem == lemma) out[rd] = positions
            }
            return out
        }

        override fun byReading(readingHira: String): List<Int>? {
            val sets = table.filterKeys { it.substringAfter("\t") == readingHira }.values
            if (sets.isEmpty() || sets.map { it.toSet() }.toSet().size != 1) return null
            return sets.first()
        }
    }

    private fun units(
        text: String,
        tokens: List<JrpMorpheme>,
        acc: JrpAccentIndex,
        words: Map<String, List<JrpMorpheme>> = emptyMap(),
        conjAccent: Boolean = false,
    ): List<JrpUnit> = JrpTokenize.units(text, FakeTokenizer(words + (text to tokens)), acc, conjAccent)

    private fun word(surface: String, pos0: String, reading: String) = surface to listOf(m(surface, pos0, reading))

    private fun plain(surface: String, reading: String = surface) =
        JrpUnit(surface, surface, reading, reading, isContent = false, isYougen = false)

    @Test
    fun counterMergeJoinsKnownCompounds() {
        val acc = FakeAccentIndex("数日\tすうじつ" to listOf(0))
        val out = units("数日", listOf(m("数", "接頭辞", "スウ"), m("日", "接尾辞", "ジツ")), acc)
        assertEquals(listOf(JrpUnit("数日", "数日", "すうじつ", "すうじつ", true, false, listOf(0))), out)
    }

    @Test
    fun kosoadoIuIsOneBlockEvenOnADictMiss() {
        val acc = FakeAccentIndex("そういう\tそういう" to listOf(0))
        val hit = units("そういう", listOf(m("そう", "副詞", "ソウ"), m("いう", "動詞", "イウ", pos1 = "一般")), acc)
        assertEquals(listOf(JrpUnit("そういう", "そういう", "そういう", "そういう", true, false, listOf(0))), hit)

        val miss = units("どう言う", listOf(m("どう", "副詞", "ドウ"), m("言う", "動詞", "イウ")), FakeAccentIndex())
        assertEquals(listOf(JrpUnit("どう言う", "どう言う", "どういう", "どういう", true, false)), miss)
    }

    @Test
    fun idiomMergeUsesTheUnambiguousDictReading() {
        val acc = FakeAccentIndex("目に余る\tめにあまる" to listOf(1, 4))
        val out = units(
            "目に余る",
            listOf(m("目", "名詞", "メ"), m("に", "助詞", "ニ"), m("余る", "動詞", "アマル")),
            acc,
        )
        assertEquals(listOf(JrpUnit("目に余る", "目に余る", "めにあまる", "めにあまる", true, false, listOf(1, 4))), out)
    }

    @Test
    fun nominalMergeByHeadwordAndByDictSurface() {
        val headword = units(
            "国際空港",
            listOf(m("国際", "名詞", "コクサイ"), m("空港", "名詞", "クウコウ")),
            FakeAccentIndex("国際空港\tこくさいくうこう" to listOf(5)),
        )
        assertEquals(listOf(JrpUnit("国際空港", "国際空港", "こくさいくうこう", "こくさいくうこう", true, false, listOf(5))), headword)

        // Sudachi mis-read a part (てんかいち+ひん): the dict's own reading wins
        val surface = units(
            "天下一品",
            listOf(m("天下一", "名詞", "テンカイチ"), m("品", "名詞", "ヒン")),
            FakeAccentIndex("天下一品\tてんかいっぴん" to listOf(5)),
        )
        assertEquals(listOf(JrpUnit("天下一品", "天下一品", "てんかいっぴん", "てんかいっぴん", true, false, listOf(5))), surface)
    }

    @Test
    fun honorificNominalIsHeibanWhenTheStemIsKnown() {
        // 香 reads かおり inside お香 but こう alone; the isolated stem reading is used
        val acc = FakeAccentIndex("香\tこう" to listOf(1))
        val out = units(
            "お香",
            listOf(m("お", "接頭辞", "オ"), m("香", "名詞", "カオリ")),
            acc,
            words = mapOf(word("香", "名詞", "コウ")),
        )
        assertEquals(listOf(JrpUnit("お香", "お香", "おこう", "おこう", true, false, listOf(0))), out)
    }

    @Test
    fun honorificNominalStaysFuriganaOnlyForPersonHonorificsAndUnknownStems() {
        val acc = FakeAccentIndex("両親\tりょうしん" to listOf(1))
        val person = units(
            "ご両親",
            listOf(m("ご", "接頭辞", "ゴ"), m("両親", "名詞", "リョウシン")),
            acc,
            words = mapOf(word("両親", "名詞", "リョウシン")),
        )
        assertEquals(listOf(JrpUnit("ご両親", "ご両親", "ごりょうしん", "ごりょうしん", true, false)), person)

        val unknown = units(
            "おばあちゃん",
            listOf(m("お", "接頭辞", "オ"), m("ばあ", "名詞", "バア"), m("ちゃん", "接尾辞", "チャン")),
            FakeAccentIndex(),
            words = mapOf(word("ばあちゃん", "名詞", "バアチャン")),
        )
        assertEquals(listOf(JrpUnit("おばあちゃん", "おばあちゃん", "おばあちゃん", "おばあちゃん", true, false)), unknown)
    }

    @Test
    fun prefixMergeWrapsAVerbStemWhenTheWholeIsAHeadword() {
        val acc = FakeAccentIndex("お出まし\tおでまし" to listOf(0), "出る\tでる" to listOf(1))
        val out = units(
            "お出まし",
            listOf(m("お", "接頭辞", "オ"), m("出まし", "動詞", "デマシ", dict = "出る")),
            acc,
            words = mapOf(word("出る", "動詞", "デル")),
        )
        assertEquals(listOf(JrpUnit("お出まし", "お出まし", "おでまし", "おでまし", true, false, listOf(0))), out)
    }

    private val wakaranakatta = listOf(
        m("分から", "動詞", "ワカラ", dict = "分かる", pos1 = "一般"),
        m("なかっ", "助動詞", "ナカッ", dict = "ない"),
        m("た", "助動詞", "タ"),
    )

    @Test
    fun yougenMergeKeepsTheDictionaryAccent() {
        val acc = FakeAccentIndex("分かる\tわかる" to listOf(2))
        val out = units("分からなかった", wakaranakatta, acc, words = mapOf(word("分かる", "動詞", "ワカル")))
        assertEquals(listOf(JrpUnit("分からなかった", "分かる", "わからなかった", "わかる", true, true, listOf(2))), out)
    }

    @Test
    fun yougenMergeComputesTheConjugatedDownstep() {
        val acc = FakeAccentIndex("分かる\tわかる" to listOf(2))
        val out = units("分からなかった", wakaranakatta, acc, words = mapOf(word("分かる", "動詞", "ワカル")), conjAccent = true)
        // 中高 + なかった: verb stem's last 自立拍 = 7 - 4
        assertEquals(
            listOf(JrpUnit("分からなかった", "分かる", "わからなかった", "わからなかった", true, true, listOf(3), conjComputed = true)),
            out,
        )
    }

    @Test
    fun yougenMergeDepitchesStoplistedAndUsesLexicalizedForms() {
        val said = units(
            "言った",
            listOf(m("言っ", "動詞", "イッ", dict = "言う"), m("た", "助動詞", "タ")),
            FakeAccentIndex("言う\tいう" to listOf(0)),
        )
        assertEquals(listOf(JrpUnit("言った", "言う", "いった", "いった", true, false)), said)

        val omowazu = units(
            "思わず",
            listOf(m("思わ", "動詞", "オモワ", dict = "思う"), m("ず", "助動詞", "ズ")),
            FakeAccentIndex("思わず\tおもわず" to listOf(2)),
        )
        assertEquals(listOf(JrpUnit("思わず", "思わず", "おもわず", "おもわず", true, false, listOf(2))), omowazu)
    }

    @Test
    fun yougenWithoutKnownAccentFallsBackPerMorpheme() {
        val out = units(
            "ググった",
            listOf(m("ググっ", "動詞", "ググッ", dict = "ググる"), m("た", "助動詞", "タ")),
            FakeAccentIndex(),
            words = mapOf(word("ググる", "動詞", "ググル")),
        )
        assertEquals(
            listOf(JrpUnit("ググっ", "ググる", "ぐぐっ", "ぐぐる", true, true), plain("た")),
            out,
        )
    }

    @Test
    fun dictMissKeepsAWordWholeWithAnUnambiguousHomophone() {
        val acc = FakeAccentIndex("解放感\tかいほうかん" to listOf(3))
        val out = units(
            "開放感",
            listOf(m("開放感", "名詞", "カイホウカン")),
            acc,
            words = mapOf(word("開放感", "名詞", "カイホウカン")),
        )
        assertEquals(listOf(JrpUnit("開放感", "開放感", "かいほうかん", "かいほうかん", true, false, listOf(3))), out)
    }

    @Test
    fun dictMissResplitsInModeAOnlyWhenEveryPartIsPitched() {
        val parts = listOf(m("前途", "名詞", "ゼント"), m("多難", "名詞", "タナン"))
        val words = mapOf(
            word("前途多難", "名詞", "ゼントタナン"),
            word("前途", "名詞", "ゼント"),
            word("多難", "名詞", "タナン"),
        )
        val tokens = listOf(m("前途多難", "名詞", "ゼントタナン", split = parts))
        val split = units("前途多難", tokens, FakeAccentIndex("前途\tぜんと" to listOf(1), "多難\tたなん" to listOf(0)), words)
        assertEquals(
            listOf(
                JrpUnit("前途", "前途", "ぜんと", "ぜんと", true, false, listOf(1)),
                JrpUnit("多難", "多難", "たなん", "たなん", true, false, listOf(0)),
            ),
            split,
        )

        val kept = units("前途多難", tokens, FakeAccentIndex("前途\tぜんと" to listOf(1)), words)
        assertEquals(listOf(JrpUnit("前途多難", "前途多難", "ぜんとたなん", "ぜんとたなん", true, false)), kept)
    }

    @Test
    fun dictOracleFixesASudachiMisreading() {
        val out = units(
            "綾",
            listOf(m("綾", "名詞", "リョウ")),
            FakeAccentIndex("綾\tあや" to listOf(1)),
            words = mapOf(word("綾", "名詞", "リョウ")),
        )
        assertEquals(listOf(JrpUnit("綾", "綾", "あや", "あや", true, false, listOf(1))), out)
    }

    @Test
    fun readingOverrideWinsAndSkipsTheOracle() {
        val hit = units("私", listOf(m("私", "代名詞", "ワタクシ")), FakeAccentIndex("私\tわたし" to listOf(0)))
        assertEquals(listOf(JrpUnit("私", "私", "わたし", "わたし", true, false, listOf(0))), hit)

        // no accent for わたし: the oracle must not swap in the dict's only reading
        val miss = units("私", listOf(m("私", "代名詞", "ワタクシ")), FakeAccentIndex("私\tわたくし" to listOf(0)))
        assertEquals(listOf(JrpUnit("私", "私", "わたし", "わたし", true, false)), miss)
    }

    @Test
    fun boundAuxiliaryFragmentsAreNotContent() {
        val out = units(
            "してき",
            listOf(
                m("し", "動詞", "シ", dict = "する", pos1 = "非自立可能"),
                m("て", "助詞", "テ", pos1 = "接続助詞"),
                m("き", "動詞", "キ", dict = "来る", pos1 = "非自立可能"),
            ),
            FakeAccentIndex("来る\tくる" to listOf(1)),
        )
        assertEquals(
            listOf(
                JrpUnit("し", "する", "し", "し", false, false),
                plain("て"),
                JrpUnit("き", "来る", "き", "き", false, false),
            ),
            out,
        )
    }

    @Test
    fun noPitchWordsKeepFuriganaAndNoFuriganaWordsDropIt() {
        val acc = FakeAccentIndex("時\tとき" to listOf(2), "時\tじ" to listOf(1), "何\tなに" to listOf(1))
        val out = units(
            "何時みたい",
            listOf(
                m("何", "代名詞", "ナニ"),
                m("時", "名詞", "トキ"),
                m("みたい", "形状詞", "ミタイ", pos1 = "助動詞語幹"),
            ),
            acc,
        )
        assertEquals(
            listOf(
                JrpUnit("何", "何", "何", "何", true, false),
                JrpUnit("時", "時", "とき", "とき", true, false),
                JrpUnit("みたい", "みたい", "みたい", "みたい", true, false),
            ),
            out,
        )

        val ji = units("時", listOf(m("時", "名詞", "ジ", pos1 = "助数詞可能")), acc, words = mapOf(word("時", "名詞", "ジ")))
        assertEquals(listOf(JrpUnit("時", "時", "じ", "じ", true, false, listOf(1))), ji)
    }

    @Test
    fun emptyReadingsFallBackToTheSurface() {
        val out = units("ｗ", listOf(m("ｗ", "補助記号", "")), FakeAccentIndex())
        assertEquals(listOf(plain("ｗ")), out)
    }
}
