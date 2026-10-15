package moe.antimony.hoshi.features.anki.enrich.jrp

import java.util.WeakHashMap
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpLexicon.AFFIX_POS
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpLexicon.CONTENT_POS
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpLexicon.DERIV_SUFFIXES
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpLexicon.HON_PREFIXES
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpLexicon.KOSOADO
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpLexicon.LIGHT_LEMMAS
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpLexicon.NOMINAL_POS
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpLexicon.NO_ABSORB
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpLexicon.NO_FURIGANA
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpLexicon.NO_PITCH_FORMS
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpLexicon.NO_PITCH_LEMMAS
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpLexicon.PERSON_HONORIFICS
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpLexicon.READING_OVERRIDE
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpLexicon.YOUGEN_POS

/**
 * Sudachi mode C tokenization to [JrpUnit]s with lemma-reading accent lookup and a mode C to mode A
 * split-and-retry cascade (port of `jrp_fill/tokenize.py`; keep the two in sync).
 *
 * Dict misses (~1%) are left with no accent (furigana only); they are mostly function words, slang or
 * proper nouns that don't need a pitch graph.
 */
internal object JrpTokenize {
    /** Phrasal idioms rarely run past a handful of morphemes. */
    private const val IDIOM_MAX = 8
    private const val LEMMA_CACHE_SIZE = 4096

    private val NOMINAL_OR_PREFIX = NOMINAL_POS + "接頭辞"
    private val NOMINAL_OR_AFFIX = NOMINAL_POS + AFFIX_POS
    private val CONJ_OK = setOf("た", "だ", "ない", "ば", "れる", "られる", "せる", "させる")

    /** `_lemma_reading` results per tokenizer (lru_cache in Python; speed only). */
    private val lemmaCaches = WeakHashMap<JrpTokenizer, LinkedHashMap<String, String>>()

    fun units(text: String, tokenizer: JrpTokenizer, acc: JrpAccentIndex, conjAccent: Boolean = false): List<JrpUnit> =
        Run(tokenizer, acc, conjAccent).units(text)

    private fun lemmaCache(tokenizer: JrpTokenizer): LinkedHashMap<String, String> = synchronized(lemmaCaches) {
        lemmaCaches.getOrPut(tokenizer) {
            object : LinkedHashMap<String, String>(256, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > LEMMA_CACHE_SIZE
            }
        }
    }

    /** Python's `a or b` for strings: empty and missing both fall through. */
    private inline fun String?.or(fallback: () -> String): String = if (isNullOrEmpty()) fallback() else this

    /** Python's `a or b` for accent lists: empty and missing both fall through. */
    private inline fun List<Int>?.or(fallback: () -> List<Int>?): List<Int>? = if (isNullOrEmpty()) fallback() else this

    private fun JrpMorpheme.pos0(): String = partOfSpeech()[0]

    private fun isBoundAux(pos0: String, surface: String, lemma: String): Boolean {
        // single-mora kana verb fragment (し, い, さ, き...): always a conjugation artifact, never a
        // standalone word worth a pitch graph
        if (pos0 == "動詞" && surface.length == 1 && surface[0] in '぀'..'ヿ') return true
        return lemma in LIGHT_LEMMAS
    }

    /** Joined surface and joined hira reading over a span of morphemes. */
    private fun joinSurfaceReading(ms: List<JrpMorpheme>): Pair<String, String> {
        val surf = ms.joinToString("") { it.surface() }
        val read = JrpKana.hira(ms.joinToString("") { it.readingForm().or { it.surface() } })
        return surf to read
    }

    /**
     * If the dict knows exactly one reading for this word, return it. Lets the dict correct a reading
     * Sudachi got wrong (綾: Sudachi りょう, dict only あや) without per-word hardcoding. Ambiguous words
     * (私: わたし & わたくし) give null.
     */
    private fun dictReading(lemma: String, acc: JrpAccentIndex): Pair<String, List<Int>>? {
        val known = acc.readingsFor(lemma)
        if (known.size == 1) {
            val (reading, positions) = known.entries.first()
            return reading to positions
        }
        return null
    }

    /**
     * A morpheme that continues the 用言: auxiliaries (た, ない, ます, れる, させ...), bound 形容詞/動詞
     * (the negative ない and 補助動詞 いる/くる/おく/しまう...) and the て/で connector that links them, so
     * the whole conjugated form (生きてきた, 祀っている) becomes one unit.
     */
    private fun isAuxTail(m: JrpMorpheme): Boolean {
        val pos = m.partOfSpeech()
        if (pos[0] == "助動詞") return m.dictionaryForm() !in NO_ABSORB
        if (pos[0] == "助詞" && pos[1] == "接続助詞" && m.surface() in setOf("て", "で", "ば")) return true
        return pos[0] in YOUGEN_POS && pos[1] == "非自立可能"
    }

    /**
     * Classify the conjugation (outer form + derivation) from the morpheme chain and compute the real
     * downstep, or null to fall back to the dict-form base. Conservative: only clean, common patterns;
     * anything stacked or unsupported (たい, ました, ません, です, unabsorbed て/ば...) gives null.
     */
    private fun conjDownstep(
        head: JrpMorpheme,
        tail: List<JrpMorpheme>,
        heiban: Boolean,
        dictAcc: Int,
        lemread: String,
        conjReading: String,
    ): Int? {
        val lemmas = tail.map { it.dictionaryForm().or { it.surface() } }
        val m = JrpConjugate.countMoras(conjReading)
        val last = lemmas.last()
        val prev = if (lemmas.size >= 2) lemmas[lemmas.size - 2] else null
        // ます / ません place the downstep on their own penult regardless of what comes before, so
        // they're safe even after て+補助動詞 (教えていただけませ\ん, ありませ\ん)
        if (last == "ます" || (last == "ぬ" && "ます" in lemmas)) return m - 1
        if (last == "た" && prev == "ます") return maxOf(1, m - 2) // ました: downstep on ま
        // negative conditional 〜なければ: downstep on the な (before ければ)
        if (last == "ば" && prev == "ない") return maxOf(1, m - 3)
        // other endings: only clean inflectional chains; reject て/補助動詞/modals so we don't
        // mis-accent (べき, よう, ましょう, ている-as-past...)
        if (lemmas.any { it !in CONJ_OK }) return null
        val form = when {
            last == "ば" -> "ba"
            last == "た" || last == "だ" -> if (prev == "ない") "nakatta" else "ta"
            last == "ない" -> "nai"
            last in DERIV_SUFFIXES -> "dict" // derived verb in plain present (断られる)
            else -> return null
        }
        val derived = lemmas.any { it in DERIV_SUFFIXES }
        if (head.pos0() == "形容詞") {
            if (derived) return null
            return JrpConjugate.adjDownstep(heiban, form, JrpConjugate.countMoras(lemread) - 1)
        }
        val atamadaka = dictAcc == 1 && !derived
        return JrpConjugate.verbDownstep(heiban, atamadaka, form, m)
    }

    private class Run(
        private val tokenizer: JrpTokenizer,
        private val acc: JrpAccentIndex,
        private val conjAccent: Boolean,
    ) {
        private val cache = lemmaCache(tokenizer)

        /**
         * Re-tokenize a word in isolation to its hira reading. Isolating it fixes Sudachi's
         * context-dependent mis-reads (香 reads かおり inside お香 but こう alone), so a lemma or an
         * honorific stem looked up on its own gets the right reading.
         */
        private fun lemmaReading(word: String): String {
            synchronized(cache) { cache[word] }?.let { return it }
            val reading = JrpKana.hira(tokenizer.tokenizeC(word).joinToString("") { it.readingForm().or { it.surface() } })
            synchronized(cache) { cache[word] = reading }
            return reading
        }

        private fun toUnit(m: JrpMorpheme): JrpUnit {
            val pos = m.partOfSpeech()
            val pos0 = pos[0]
            val surface = m.surface()
            val ov = READING_OVERRIDE[surface]
            var reading = ov.or { JrpKana.hira(m.readingForm().or { surface }) }
            val lemma = m.dictionaryForm().or { surface }
            val isContent = pos0 in CONTENT_POS
            if (!isContent || isBoundAux(pos0, surface, lemma)) {
                return JrpUnit(surface, lemma, reading, reading, isContent = false, isYougen = false)
            }
            // modal stems (evidential/likeness よう, みたい = 形状詞/助動詞語幹) are grammatical: no pitch;
            // reading-aware drops for polysemous kanji formal nouns
            if (lemma in NO_PITCH_LEMMAS || pos[1] == "助動詞語幹" || (lemma to reading) in NO_PITCH_FORMS) {
                if (lemma in NO_FURIGANA) reading = surface // ...and drop the reading too
                return JrpUnit(surface, lemma, reading, reading, isContent = true, isYougen = false)
            }
            var lemread = ov.or { lemmaReading(lemma) }
            var positions = acc.lookup(lemma, lemread).or { acc.lookup(lemma, reading) }
            // dict oracle: non-inflecting word whose Sudachi reading the dict doesn't know -> adopt
            // the dict's reading if it's unambiguous (fixes 綾 -> あや)
            if (positions.isNullOrEmpty() && pos0 !in YOUGEN_POS && ov == null) {
                val oracle = dictReading(lemma, acc)
                if (oracle != null) {
                    reading = oracle.first
                    lemread = oracle.first
                    positions = oracle.second
                }
            }
            return JrpUnit(
                surface, lemma, reading, lemread, isContent = true, isYougen = pos0 in YOUGEN_POS,
                accents = positions.orEmpty(),
            )
        }

        /**
         * Fuse a 動詞/形容詞 head with its trailing inflection chain into one yougen unit
         * (分から+なかっ+た -> 分からなかった). The accent stays the dictionary-form downstep; the emitter
         * renders the dict reading as the JRP base. Null (caller uses the per-morpheme path) unless the
         * head is a real content yougen whose lemma has a known accent, so coverage is never lost.
         */
        private fun mergeYougen(ms: List<JrpMorpheme>, i: Int): Pair<JrpUnit, Int>? {
            val head = ms[i]
            val pos0 = head.pos0()
            val surface = head.surface()
            val lemma = head.dictionaryForm().or { surface }
            if (pos0 !in YOUGEN_POS || isBoundAux(pos0, surface, lemma)) return null
            var j = i + 1
            while (j < ms.size && isAuxTail(ms[j])) j += 1
            if (j == i + 1) return null // bare verb, no tail: the per-morpheme path handles it
            val (surf, read) = joinSurfaceReading(ms.subList(i, j))
            if (lemma in NO_PITCH_LEMMAS) { // 言った etc.: furigana, no pitch
                return JrpUnit(surf, lemma, read, read, isContent = true, isYougen = false) to j - i
            }
            // lexicalized whole form (思わず, 我ながら...): the inflected span is itself a dict headword,
            // so use its own accent as a plain unit, not the verb's (and it isn't a 用言)
            val whole = acc.lookup(surf, read)
            if (whole != null) {
                return JrpUnit(surf, surf, read, read, isContent = true, isYougen = false, accents = whole) to j - i
            }
            val lemread = READING_OVERRIDE[surface].or { lemmaReading(lemma) }
            val positions = acc.lookup(lemma, lemread).or {
                acc.lookup(lemma, JrpKana.hira(head.readingForm().or { surface }))
            }
            if (positions.isNullOrEmpty()) return null
            if (conjAccent) {
                val ds = conjDownstep(head, ms.subList(i + 1, j), positions[0] == 0, positions[0], lemread, read)
                if (ds != null) {
                    // computed downstep sits on the conjugated reading itself: no base
                    return JrpUnit(
                        surf, lemma, read, read, isContent = true, isYougen = true,
                        accents = listOf(ds), conjComputed = true,
                    ) to j - i
                }
            }
            return JrpUnit(surf, lemma, read, lemread, isContent = true, isYougen = true, accents = positions) to j - i
        }

        /**
         * Sudachi splits some number/noun + 接尾辞 compounds (数日 -> 数+日). If the joined (surface,
         * reading) is in the accent dict, pitch it as one unit. Self-validating: 数日->すうじつ hits,
         * 二日->ふたか misses (stays safe, never producing a wrong reading).
         */
        private fun counterMerge(m: JrpMorpheme, nxt: JrpMorpheme): JrpUnit? {
            if (nxt.pos0() != "接尾辞") return null
            val (js, jr) = joinSurfaceReading(listOf(m, nxt))
            val pos = acc.lookup(js, jr)
            return if (!pos.isNullOrEmpty()) JrpUnit(js, js, jr, jr, isContent = true, isYougen = false, accents = pos) else null
        }

        /**
         * こう/そう/ああ/どう + いう -> one 連体詞 block (そういう, こういう...), even if the dict lacks it;
         * pitched when present (そういう[0], どういう[1]).
         */
        private fun kosoadoIuMerge(m: JrpMorpheme, nxt: JrpMorpheme): JrpUnit? {
            if (m.surface() !in KOSOADO || nxt.pos0() != "動詞") return null
            if (nxt.dictionaryForm() !in setOf("いう", "言う")) return null
            val (js, jr) = joinSurfaceReading(listOf(m, nxt))
            val pos = acc.lookup(js, jr)
            return JrpUnit(js, js, jr, jr, isContent = true, isYougen = false, accents = pos.orEmpty())
        }

        /**
         * お/ご + content that Sudachi split (お話, お出まし, ご相談) -> one unit, but only when the joined
         * (surface, reading) is a dict headword (self-validating, so the dict's whole-word accent wins:
         * おはなし[0], not the split はなし[3]). The result is a plain noun even if it wraps a verb stem.
         */
        private fun prefixMerge(ms: List<JrpMorpheme>, i: Int): Pair<JrpUnit, Int>? {
            val m = ms[i]
            if (m.pos0() != "接頭辞" || m.surface() !in HON_PREFIXES || i + 1 >= ms.size) return null
            val preS = m.surface()
            val preR = JrpKana.hira(m.readingForm().or { preS })
            val yg = mergeYougen(ms, i + 1)
            val fs: String
            val fr: String
            val n: Int
            if (yg != null) {
                fs = yg.first.surface
                fr = yg.first.reading
                n = yg.second
            } else {
                val follow = toUnit(ms[i + 1])
                fs = follow.surface
                fr = follow.reading
                n = 1
            }
            val js = preS + fs
            val jr = preR + fr
            val pos = acc.lookup(js, jr)
            if (!pos.isNullOrEmpty()) {
                return JrpUnit(js, js, jr, jr, isContent = true, isYougen = false, accents = pos) to 1 + n
            }
            return null
        }

        /**
         * Fuse a particle-containing phrase whose whole surface is a dict headword into one unit
         * (目に余る -> めにあまる[1,4]). Longest span first, gated to real verb phrases: ends in a 用言 and
         * carries an interior 助詞, which excludes the こと/もの formal nouns and noun+の coincidences.
         * Uses the dict reading only when unambiguous, so a sub-morpheme mis-read can't leak in.
         */
        private fun idiomMerge(ms: List<JrpMorpheme>, i: Int): Pair<JrpUnit, Int>? {
            if (ms[i].pos0() !in CONTENT_POS) return null
            val hi = minOf(i + IDIOM_MAX, ms.size)
            for (j in hi downTo i + 3) { // >= 3 morphemes: head + 助詞 + 用言
                if (ms[j - 1].pos0() !in YOUGEN_POS) continue
                if ((i + 1 until j - 1).none { ms[it].pos0() == "助詞" }) continue
                val surf = ms.subList(i, j).joinToString("") { it.surface() }
                val ds = dictReading(surf, acc)
                if (ds != null) {
                    return JrpUnit(surf, surf, ds.first, ds.first, isContent = true, isYougen = false, accents = ds.second) to j - i
                }
            }
            return null
        }

        /**
         * Fuse adjacent nominal morphemes Sudachi over-split, longest window first: (a) the joined
         * surface+reading is a dict headword -> pitched with its accent (我が国 -> わがくに[1]); (b) the
         * run carries an honorific 接頭辞 (お/ご) -> kept whole even on a dict miss, furigana-only
         * (おばあちゃん). Verb stems stop the window, so お出まし stays for [prefixMerge].
         */
        private fun nominalMerge(ms: List<JrpMorpheme>, i: Int): Pair<JrpUnit, Int>? {
            val n = ms.size
            if (ms[i].pos0() !in NOMINAL_OR_PREFIX) return null
            var j = i
            while (j < n && ms[j].pos0() in NOMINAL_OR_AFFIX) j += 1
            while (j - i >= 2) {
                val (surf, read) = joinSurfaceReading(ms.subList(i, j))
                val pos = acc.lookup(surf, read).or { acc.byReading(read) }
                if (!pos.isNullOrEmpty()) {
                    return JrpUnit(surf, surf, read, read, isContent = true, isYougen = false, accents = pos) to j - i
                }
                val hasPrefix = (i until j).any { ms[it].pos0() == "接頭辞" }
                // surface is a dict headword but Sudachi mis-read a sub-morpheme, so the reading-keyed
                // lookups missed (天下一品: てんかいち+ひん != てんかいっぴん): adopt the dict's own reading +
                // accent when unambiguous. 接頭辞 spans belong to the honorific branch below (お酒 is おさけ
                // here, not the dict's rare お酒=ごしゅ headword).
                if (!hasPrefix) {
                    val ds = dictReading(surf, acc)
                    if (ds != null) {
                        return JrpUnit(surf, surf, ds.first, ds.first, isContent = true, isYougen = false, accents = ds.second) to j - i
                    }
                }
                if (hasPrefix) {
                    var rd = read
                    var accents = emptyList<Int>()
                    val pre = ms[i]
                    // honorific お/ご compound the dict omits = transparent 美化語 -> 平板 (0), but only when
                    // the stem is itself a known dict word and not a person-honorific (those keep their
                    // own accent)
                    if (pre.pos0() == "接頭辞" && pre.surface() in HON_PREFIXES) {
                        val stem = surf.substring(pre.surface().length)
                        val sr = if (stem.isNotEmpty()) lemmaReading(stem) else ""
                        if (stem.isNotEmpty() && stem !in PERSON_HONORIFICS &&
                            (acc.lookup(stem, sr) != null || acc.readingsFor(stem).isNotEmpty())
                        ) {
                            rd = JrpKana.hira(pre.readingForm().or { pre.surface() }) + sr
                            accents = listOf(0)
                        }
                    }
                    return JrpUnit(surf, surf, rd, rd, isContent = true, isYougen = false, accents = accents) to j - i
                }
                j -= 1
            }
            return null
        }

        /**
         * A content unit Sudachi kept whole but the accent dict misses: try to recover a pitch. Returns
         * the replacement unit(s), or null to keep [u] furigana-only. First trust mode C: if the exact
         * reading is an unambiguous dict homophone (開放感/解放感 -> かいほうかん[3]) keep the word whole
         * with that accent. Otherwise fall back to a mode A re-split, but only when every sub-unit is
         * non-content or accented (so the split never drops a content word's pitch).
         */
        private fun resolveDictMiss(u: JrpUnit, m: JrpMorpheme): List<JrpUnit>? {
            if (!u.isYougen && JrpKana.KANJI_RE.containsMatchIn(u.surface) &&
                u.lemma !in NO_PITCH_LEMMAS && (u.lemma to u.reading) !in NO_PITCH_FORMS
            ) {
                val rb = acc.byReading(u.reading)
                if (rb != null) {
                    return listOf(JrpUnit(u.surface, u.lemma, u.reading, u.reading, isContent = true, isYougen = false, accents = rb))
                }
            }
            val subs = m.splitA()
            if (subs.size > 1) {
                val subUnits = subs.map { toUnit(it) }
                if (subUnits.all { !it.isContent || it.accents.isNotEmpty() }) return subUnits
            }
            return null
        }

        fun units(text: String): List<JrpUnit> {
            val ms = tokenizer.tokenizeC(text)
            val out = ArrayList<JrpUnit>()
            var i = 0
            while (i < ms.size) {
                val m = ms[i]
                if (i + 1 < ms.size) {
                    val merged = counterMerge(m, ms[i + 1]) ?: kosoadoIuMerge(m, ms[i + 1])
                    if (merged != null) {
                        out += merged
                        i += 2
                        continue
                    }
                }
                val span = idiomMerge(ms, i) ?: nominalMerge(ms, i) ?: prefixMerge(ms, i) ?: mergeYougen(ms, i)
                if (span != null) {
                    out += span.first
                    i += span.second
                    continue
                }
                val u = toUnit(m)
                if (u.isContent && u.accents.isEmpty()) {
                    val replacement = resolveDictMiss(u, m)
                    if (replacement != null) {
                        out += replacement
                        i += 1
                        continue
                    }
                }
                out += u
                i += 1
            }
            return out
        }
    }
}
