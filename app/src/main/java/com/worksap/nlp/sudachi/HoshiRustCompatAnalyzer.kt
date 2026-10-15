package com.worksap.nlp.sudachi

import com.worksap.nlp.sudachi.dictionary.CategoryType
import com.worksap.nlp.sudachi.dictionary.WordInfo
import java.text.Normalizer
import java.util.EnumSet
import java.util.Locale

/*
 * Mode C analysis that reproduces sudachi.rs 0.6.11 (the engine behind SudachiPy) on top of Java
 * Sudachi's dictionary reader, input-text plugins and OOV plugins.
 *
 * Java Sudachi 0.7.x reads the same dictionaries but its analyzer differs from sudachi.rs in ways that
 * change real tokenizations, so the lattice and path rewriting live here instead of
 * JapaneseTokenizer:
 *  - BOW rules: sudachi.rs forbids a dictionary word from ending before a NOOOVBOW character (ー, small
 *    katakana, 々, combining marks...) and supports NOOOVBOW2 (this and the next character); Java only
 *    applies NOOOVBOW to OOV starts and has no NOOOVBOW2.
 *  - Category continuity is computed back to front, and code point lengths count characters, not UTF-16
 *    units.
 *  - Connections are never skipped as inhibited; they simply cost 32767.
 *  - JoinKatakanaOov always builds a new OOV node (Java reuses an existing lattice node with the same
 *    span, such as a proper noun).
 *  - JoinNumeric concatenates the raw (unsubstituted) reading and dictionary forms.
 *  - Default input normalization lowercases with the full Unicode mapping only Uppercase characters
 *    (İ -> i̇, titlecase ǅ is left to NFKC) and checks the ignore list against the original character.
 *
 * This file sits in Sudachi's package because the lattice node, lexicon lookup and morpheme list types
 * it reuses are package-private.
 */

/** Character categories with sudachi.rs bit semantics (ALL, NOOOVBOW2), parsed from a char.def file. */
internal class HoshiRustCharCategory private constructor(
    private val boundaries: IntArray,
    private val categories: IntArray,
) {
    /** Categories of [codePoint]: the union of every range containing it, DEFAULT when none. */
    fun of(codePoint: Int): Int {
        var lo = 0
        var hi = boundaries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (boundaries[mid] <= codePoint) lo = mid + 1 else hi = mid
        }
        // lo = number of boundaries <= codePoint; interval index lo - 1 starts at boundaries[lo - 1]
        return if (lo == 0) DEFAULT else categories[lo - 1]
    }

    companion object {
        const val DEFAULT = 1
        const val ALPHA = 1 shl 5
        const val KATAKANA = 1 shl 7
        const val NUMERIC = 1 shl 4
        const val KANJINUMERIC = 1 shl 8
        const val GREEK = 1 shl 9
        const val CYRILLIC = 1 shl 10
        const val NOOOVBOW = 1 shl 30
        const val NOOOVBOW2 = 1 shl 31
        const val ALL = 0x3FFF_FFFF

        /** Java category for each named Rust bit (DEFAULT..USER4 share Java's ordinals). */
        private val NAMED: List<Pair<Int, CategoryType>> =
            CategoryType.values().filter { it != CategoryType.NOOOVBOW }.map { (1 shl it.ordinal) to it } +
                (NOOOVBOW to CategoryType.NOOOVBOW)

        fun bit(name: String): Int = when (name) {
            "ALL" -> ALL
            "NOOOVBOW" -> NOOOVBOW
            "NOOOVBOW2" -> NOOOVBOW2
            else -> 1 shl CategoryType.valueOf(name).ordinal.also {
                require(it <= 14) { "unsupported category $name" }
            }
        }

        fun toJava(bits: Int): Set<CategoryType> {
            val out = EnumSet.noneOf(CategoryType::class.java)
            for ((b, t) in NAMED) if (bits and b != 0) out.add(t)
            return out
        }

        fun parse(charDef: String): HoshiRustCharCategory {
            val begins = ArrayList<Int>()
            val ends = ArrayList<Int>()
            val cats = ArrayList<Int>()
            for (raw in charDef.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || !line.startsWith("0x")) continue
                val cols = line.split(Regex("\\s+"))
                val r = cols[0].split("..")
                val begin = r[0].removePrefix("0x").toInt(16)
                val end = if (r.size > 1) r[1].removePrefix("0x").toInt(16) + 1 else begin + 1
                var c = 0
                for (col in cols.drop(1)) {
                    if (col.startsWith("#")) break
                    c = c or bit(col)
                }
                begins += begin
                ends += end
                cats += c
            }
            val bounds = (begins + ends).toSortedSet().toIntArray()
            val categories = IntArray(bounds.size)
            for (k in begins.indices) {
                for (i in bounds.indices) {
                    if (bounds[i] >= begins[k] && bounds[i] < ends[k]) categories[i] = categories[i] or cats[k]
                }
            }
            for (i in categories.indices) if (categories[i] == 0) categories[i] = DEFAULT
            return HoshiRustCharCategory(bounds, categories)
        }

        /**
         * The same char.def in the syntax Java Sudachi 0.7 accepts: ALL spelled out, NOOOVBOW2 as
         * NOOOVBOW. Java only uses it for the plugins' KANJI/HIRAGANA/KATAKANA tests and MeCab's
         * category list; every BOW decision goes through [HoshiRustCharCategory].
         */
        fun javaCompatible(charDef: String): String {
            val all = CategoryType.values().filter { it != CategoryType.NOOOVBOW }.joinToString(" ") { it.name }
            return charDef.lineSequence().joinToString("\n") { line ->
                if (!line.startsWith("0x")) {
                    line
                } else {
                    val comment = line.indexOf('#').let { if (it < 0) line.length else it }
                    line.substring(0, comment).split(Regex("\\s+")).filter { it.isNotEmpty() }
                        .joinToString(" ") { tok ->
                            when (tok) {
                                "ALL" -> all
                                "NOOOVBOW2" -> "NOOOVBOW"
                                else -> tok
                            }
                        }
                }
            }
        }
    }
}

/**
 * [UTF8InputText] seen through sudachi.rs semantics: categories, continuity, BOW and code point lengths.
 * Offsets are UTF-8 byte offsets into the modified text, as everywhere in Java Sudachi.
 */
internal class HoshiRustInputText(
    val base: UTF8InputText,
    categoryTable: HoshiRustCharCategory,
) : InputText {
    private val bytes: ByteArray = base.byteText
    private val charStart: IntArray
    private val byteToChar: IntArray
    val cats: IntArray
    private val canBowChar: BooleanArray
    private val continuity: IntArray
    val charCount: Int

    init {
        val text = base.text
        val cps = text.codePoints().toArray()
        charCount = cps.size
        charStart = IntArray(charCount + 1)
        byteToChar = IntArray(bytes.size + 1)
        var b = 0
        for (i in 0 until charCount) {
            charStart[i] = b
            val len = utf8Length(cps[i])
            for (k in 0 until len) byteToChar[b + k] = i
            b += len
        }
        charStart[charCount] = b
        byteToChar[bytes.size] = charCount
        check(b == bytes.size) { "byte length mismatch" }
        cats = IntArray(charCount) { categoryTable.of(cps[it]) }

        // input_text/buffer build(): BOW
        canBowChar = BooleanArray(charCount)
        val nonStarting = HoshiRustCharCategory.ALPHA or HoshiRustCharCategory.GREEK or HoshiRustCharCategory.CYRILLIC
        var prev = 0
        var nextBow = true
        for (i in 0 until charCount) {
            val cat = cats[i]
            canBowChar[i] = if (!nextBow) {
                nextBow = true
                false
            } else if (cat and HoshiRustCharCategory.NOOOVBOW2 != 0) {
                nextBow = false
                false
            } else if (cat and HoshiRustCharCategory.NOOOVBOW != 0) {
                false
            } else if (cat and nonStarting != 0) {
                cat and prev == 0
            } else {
                true
            }
            prev = cat
        }

        // fill_cat_continuity(): back to front
        continuity = IntArray(charCount) { 1 }
        if (charCount > 0) {
            var cat = cats[charCount - 1]
            for (i in charCount - 2 downTo 0) {
                val common = cats[i] and cat
                if (common != 0) {
                    continuity[i] = continuity[i + 1] + 1
                    cat = common
                } else {
                    cat = cats[i]
                }
            }
        }
    }

    fun isCharStart(byteIndex: Int): Boolean =
        byteIndex == bytes.size || charStart[byteToChar[byteIndex]] == byteIndex

    fun charIndex(byteIndex: Int): Int = byteToChar[byteIndex]

    fun byteIndex(charIndex: Int): Int = charStart[charIndex]

    /** `InputBuffer::can_bow` at a byte offset (false inside a character). */
    override fun canBow(index: Int): Boolean =
        index < bytes.size && isCharStart(index) && canBowChar[byteToChar[index]]

    /** `cat_of_range`: intersection over the character range, empty for an empty range. */
    fun catOfCharRange(begin: Int, end: Int): Int {
        if (begin >= end) return 0
        var acc = -1
        for (i in begin until end) acc = acc and cats[i]
        return acc
    }

    override fun getText(): String = base.text
    override fun getOriginalText(): String = base.originalText
    override fun getSubstring(begin: Int, end: Int): String = base.getSubstring(begin, end)
    override fun slice(begin: Int, end: Int): InputText = throw UnsupportedOperationException()
    override fun getOriginalIndex(index: Int): Int = base.getOriginalIndex(index)
    override fun getNextInOriginal(index: Int): Int = base.getNextInOriginal(index)
    override fun modifiedOffset(index: Int): Int = base.modifiedOffset(index)
    override fun getByteText(): ByteArray = bytes

    override fun getCharCategoryTypes(index: Int): Set<CategoryType> =
        HoshiRustCharCategory.toJava(cats[byteToChar[index]])

    override fun getCharCategoryTypes(begin: Int, end: Int): Set<CategoryType> =
        HoshiRustCharCategory.toJava(catOfCharRange(byteToChar[begin], byteToChar[end]))

    override fun getCharCategoryContinuousLength(index: Int): Int {
        val c = byteToChar[index]
        return charStart[c + continuity[c]] - index
    }

    /** `char_distance`: at most [codePointOffset] characters, clamped to the end. */
    override fun getCodePointsOffsetLength(index: Int, codePointOffset: Int): Int {
        val c = byteToChar[index]
        return charStart[minOf(c + codePointOffset, charCount)] - index
    }

    override fun codePointCount(begin: Int, end: Int): Int = byteToChar[end] - byteToChar[begin]

    /** `get_word_candidate_length`: distance to the next BOW character (SimpleOov). */
    override fun getWordCandidateLength(index: Int): Int {
        val c = byteToChar[index]
        for (i in c + 1 until charCount) if (canBowChar[i]) return charStart[i] - index
        return bytes.size - index
    }

    private companion object {
        fun utf8Length(cp: Int): Int = when {
            cp < 0x80 -> 1
            cp < 0x800 -> 2
            cp < 0x10000 -> 3
            else -> 4
        }
    }
}

/**
 * sudachi.rs `DefaultInputTextPlugin`: rewrite.def replacements first, then lowercase (Uppercase
 * characters, full mapping) and per-character NFKC unless the character is on the ignore list.
 */
internal class HoshiRustDefaultInputText(rewriteDef: String) {
    private val ignore = HashSet<Int>()
    private val replacements = HashMap<String, String>()
    private val keyLengths = HashMap<Int, Int>()

    init {
        for (raw in rewriteDef.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val cols = line.split(Regex("\\s+"))
            when (cols.size) {
                1 -> {
                    require(cols[0].codePointCount(0, cols[0].length) == 1) { "${cols[0]} is not a character" }
                    ignore += cols[0].codePointAt(0)
                }
                2 -> {
                    require(cols[0] !in replacements) { "${cols[0]} is already defined" }
                    val first = cols[0].codePointAt(0)
                    val n = cols[0].codePointCount(0, cols[0].length)
                    if ((keyLengths[first] ?: 0) < n) keyLengths[first] = n
                    replacements[cols[0]] = cols[1]
                }
                else -> throw IllegalArgumentException("invalid rewrite.def line: $line")
            }
        }
    }

    fun rewrite(builder: InputTextBuilder) {
        val text = builder.text
        var delta = 0 // length change of the edits so far, in UTF-16 units
        var i = 0
        outer@ while (i < text.length) {
            val cp = text.codePointAt(i)
            val charLen = Character.charCount(cp)
            // 1. replacement as defined by rewrite.def (no key is a prefix of another)
            var n = keyLengths[cp] ?: 0
            while (n > 0) {
                val end = runCatching { text.offsetByCodePoints(i, n) }.getOrNull()
                if (end != null) {
                    val replace = replacements[text.substring(i, end)]
                    if (replace != null) {
                        builder.replace(i + delta, end + delta, replace)
                        delta += replace.length - (end - i)
                        i = end
                        continue@outer
                    }
                }
                n -= 1
            }
            // 2. normalization
            val ch = String(Character.toChars(cp))
            val needLower = Character.isUpperCase(cp)
            val needNfkc = cp !in ignore && !Normalizer.isNormalized(ch, Normalizer.Form.NFKC)
            if (needLower || needNfkc) {
                val lowered = if (needLower) ch.lowercase(Locale.ROOT) else ch
                val result = if (needNfkc) Normalizer.normalize(lowered, Normalizer.Form.NFKC) else lowered
                // handle_normalization_slow: replaced only when the first character changes
                if (result.isNotEmpty() && result.codePointAt(0) != cp) {
                    builder.replace(i + delta, i + charLen + delta, result)
                    delta += result.length - charLen
                }
            }
            i += charLen
        }
    }
}

/**
 * sudachi.rs mode C analysis using [dictionary]'s grammar, lexicon, input-text plugins and OOV plugins.
 * Input normalization is [normalizer]; the dictionary's own path-rewrite plugins are ignored; JoinNumeric (normalizing) and JoinKatakanaOov
 * (名詞,普通名詞,一般, minLength 3) run here with sudachi.rs semantics, matching SudachiPy's default
 * sudachi.json. Not thread-safe; instances are cheap.
 */
internal class HoshiRustCompatAnalyzer(
    private val dictionary: JapaneseDictionary,
    private val categoryTable: HoshiRustCharCategory,
    /** Runs before the dictionary's input-text plugins (which must not include Java's default one). */
    private val normalizer: HoshiRustDefaultInputText,
) {
    private val grammar = dictionary.grammar
    private val lexicon = dictionary.lexicon
    private val numericPosId = grammar.getPartOfSpeechId(listOf("名詞", "数詞", "*", "*", "*", "*"))
    private val katakanaOovPosId = grammar.getPartOfSpeechId(listOf("名詞", "普通名詞", "一般", "*", "*", "*"))
    private val katakanaMinLength = 3

    init {
        require(numericPosId >= 0 && katakanaOovPosId >= 0) { "dictionary lacks the plugin parts of speech" }
    }

    /** A lattice node plus the sudachi.rs-only facts the path rewriters need. */
    private class PathNode(
        val node: LatticeNodeImpl,
        /** `WordId::is_oov`: OOV ids and concatenated (INVALID id) nodes. */
        val rustOov: Boolean,
        /** `WordInfoData` surface/reading/dictionary form before the empty-means-surface substitution. */
        val dataSurface: String,
        val rawReading: String,
        val rawDictionaryForm: String,
    )

    private class LNode(val node: LatticeNodeImpl?, val rightId: Short, val total: Int, val prev: LNode?)

    fun analyze(text: String): MorphemeList {
        if (text.isEmpty()) return MorphemeList.EMPTY
        if (!lexicon.isValid || !grammar.isValid) throw IllegalStateException("dictionary was closed")
        val builder = UTF8InputTextBuilder(text, grammar)
        normalizer.rewrite(builder)
        for (plugin in dictionary.inputTextPlugins) plugin.rewrite(builder)
        val input = builder.build()
        val rin = HoshiRustInputText(input, categoryTable)
        var path = bestPath(rin)
        path = joinNumeric(rin, path)
        path = joinKatakanaOov(rin, path)
        return MorphemeList(input, grammar, lexicon, path.map { it.node }, true, Tokenizer.SplitMode.C)
    }

    private fun bestPath(rin: HoshiRustInputText): List<PathNode> {
        val bytes = rin.byteText
        val len = bytes.size
        val ends = arrayOfNulls<ArrayList<LNode>>(len + 1)
        ends[0] = arrayListOf(LNode(null, 0, 0, null))
        val lookup = lexicon.makeLookup()
        val oovPlugins = dictionary.oovProviderPlugins
        val oovBuffer = ArrayList<LatticeNodeImpl>()

        fun insert(node: LatticeNodeImpl) {
            var min = Int.MAX_VALUE
            var prev: LNode? = null
            val lefts = ends[node.begin]
            if (lefts != null) {
                for (l in lefts) {
                    if (l.total == Int.MAX_VALUE) continue
                    val c = l.total + grammar.getConnectCost(l.rightId, node.leftId) + node.cost
                    if (c < min) {
                        min = c
                        prev = l
                    }
                }
            }
            val list = ends[node.end] ?: ArrayList<LNode>().also { ends[node.end] = it }
            list.add(LNode(node, node.rightId, min, prev))
        }

        fun provideOovs(plugin: OovProviderPlugin, offset: Int, created: Long): Long {
            oovBuffer.clear()
            val n = plugin.provideOOV(rin, offset, created, oovBuffer)
            var mask = created
            for (k in 0 until n) {
                val node = oovBuffer[k]
                node.begin = offset
                node.end = offset + node.wordInfo.length
                insert(node)
                mask = mask or 1L
            }
            return mask
        }

        for (c in 0 until rin.charCount) {
            val b = rin.byteIndex(c)
            if (ends[b].isNullOrEmpty()) continue
            var created = 0L
            lookup.reset(bytes, b, len)
            while (lookup.next()) {
                val end = lookup.endOffset
                if (end < len && !rin.canBow(end)) continue
                val count = lookup.numWords
                val ids = lookup.wordsIds
                for (w in 0 until count) {
                    val id = ids[w]
                    val node = LatticeNodeImpl(lexicon, lexicon.getLeftId(id), lexicon.getRightId(id), lexicon.getCost(id), id)
                    node.begin = b
                    node.end = end
                    insert(node)
                    created = created or 1L
                }
            }
            if (rin.cats[c] and (HoshiRustCharCategory.NOOOVBOW or HoshiRustCharCategory.NOOOVBOW2) == 0) {
                for (plugin in oovPlugins) created = provideOovs(plugin, b, created)
            }
            if (created == 0L) created = provideOovs(oovPlugins.last(), b, created)
            if (created == 0L) throw IllegalStateException("no morpheme candidate at byte $b")
        }

        // EOS: left id 0, cost 0
        var eosPrev: LNode? = null
        var eosMin = Int.MAX_VALUE
        for (l in ends[len].orEmpty()) {
            if (l.total == Int.MAX_VALUE) continue
            val cost = l.total + grammar.getConnectCost(l.rightId, 0)
            if (cost < eosMin) {
                eosMin = cost
                eosPrev = l
            }
        }
        if (eosPrev == null) throw IllegalStateException("EOS isn't connected to BOS")
        val out = ArrayList<PathNode>()
        var cur: LNode? = eosPrev
        while (cur?.node != null) {
            out.add(pathNode(cur.node!!))
            cur = cur.prev
        }
        out.reverse()
        return out
    }

    private fun pathNode(node: LatticeNodeImpl): PathNode {
        val wi = node.wordInfo
        return if (node.isOOV) {
            // resolve_best_path(): an OOV carries only its surface and part of speech
            PathNode(node, true, wi.surface, "", "")
        } else {
            val dfwi = wi.dictionaryFormWordId
            PathNode(
                node = node,
                rustOov = false,
                dataSurface = wi.surface,
                // dictionaries store an empty reading when it equals the headword
                rawReading = if (wi.readingForm == wi.surface) "" else wi.readingForm,
                rawDictionaryForm = if (dfwi >= 0 && dfwi != node.wordId) wi.dictionaryForm else "",
            )
        }
    }

    private fun charRange(rin: HoshiRustInputText, p: PathNode): Pair<Int, Int> =
        rin.charIndex(p.node.begin) to rin.charIndex(p.node.end)

    /** `concat_nodes` (JoinNumeric): raw forms are concatenated, then empty falls back to the surface. */
    private fun concatNodes(path: MutableList<PathNode>, begin: Int, end: Int, normalized: String?): MutableList<PathNode> {
        require(begin < end)
        val surface = StringBuilder()
        val reading = StringBuilder()
        val dictionaryForm = StringBuilder()
        var length = 0
        for (p in path.subList(begin, end)) {
            surface.append(p.dataSurface)
            reading.append(p.rawReading)
            dictionaryForm.append(p.rawDictionaryForm)
            length += p.node.wordInfo.length
        }
        val norm = normalized ?: path.subList(begin, end).joinToString("") { it.node.wordInfo.normalizedForm }
        val s = surface.toString()
        val wi = WordInfo(
            s, length.toShort(), path[begin].node.wordInfo.posId, norm,
            dictionaryForm.toString().ifEmpty { s }, reading.toString().ifEmpty { s },
        )
        val node = LatticeNodeImpl()
        node.setRange(path[begin].node.begin, path[end - 1].node.end)
        node.setWordInfo(wi)
        val merged = PathNode(node, true, s, reading.toString(), dictionaryForm.toString())
        path.subList(begin, end).clear()
        path.add(begin, merged)
        return path
    }

    /** `concat_oov_nodes` (JoinKatakanaOov). */
    private fun concatOovNodes(path: MutableList<PathNode>, begin: Int, end: Int, posId: Short) {
        require(begin < end)
        val surface = StringBuilder()
        var length = 0
        var anyOov = false
        for (p in path.subList(begin, end)) {
            surface.append(p.dataSurface)
            length += p.node.wordInfo.length
            anyOov = anyOov || p.rustOov
        }
        val s = surface.toString()
        val node = LatticeNodeImpl()
        node.setRange(path[begin].node.begin, path[end - 1].node.end)
        node.setWordInfo(WordInfo(s, length.toShort(), posId, s, s, s))
        if (anyOov) node.setOOV()
        // the max word id is OOV iff some part is; otherwise (dic, MAX_WORD), which is not
        val merged = PathNode(node, anyOov, s, "", "")
        path.subList(begin, end).clear()
        path.add(begin, merged)
    }

    private fun joinNumeric(rin: HoshiRustInputText, input: List<PathNode>): List<PathNode> {
        var path = input.toMutableList()
        var beginIdx = -1
        var commaAsDigit = true
        var periodAsDigit = true
        val parser = NumericParser()

        fun concat(begin: Int, end: Int) {
            val first = path[begin].node.wordInfo
            if (first.posId != numericPosId) return
            val normalized = parser.normalized
            if (end - begin > 1 || normalized != first.normalizedForm) {
                path = concatNodes(path, begin, end, normalized)
            }
        }

        var i = -1
        while (i < path.size - 1) {
            i += 1
            val p = path[i]
            val (cb, ce) = charRange(rin, p)
            val ctypes = rin.catOfCharRange(cb, ce)
            val s = p.node.wordInfo.normalizedForm
            if (ctypes and (HoshiRustCharCategory.NUMERIC or HoshiRustCharCategory.KANJINUMERIC) != 0 ||
                (commaAsDigit && s == ",") || (periodAsDigit && s == ".")
            ) {
                if (beginIdx < 0) {
                    parser.clear()
                    beginIdx = i
                }
                for (ch in s) {
                    if (!parser.append(ch)) {
                        if (beginIdx >= 0) {
                            if (parser.errorState == NumericParser.Error.COMMA) {
                                commaAsDigit = false
                                i = beginIdx - 1
                            } else if (parser.errorState == NumericParser.Error.POINT) {
                                periodAsDigit = false
                                i = beginIdx - 1
                            }
                            beginIdx = -1
                        }
                        break
                    }
                }
                continue
            }
            if (beginIdx >= 0) {
                if (parser.done()) {
                    concat(beginIdx, i)
                    i = beginIdx + 1
                } else {
                    val ss = path[i - 1].node.wordInfo.normalizedForm
                    if ((parser.errorState == NumericParser.Error.COMMA && ss == ",") ||
                        (parser.errorState == NumericParser.Error.POINT && ss == ".")
                    ) {
                        concat(beginIdx, i - 1)
                        i = beginIdx + 2
                    }
                }
            }
            beginIdx = -1
            if (!commaAsDigit && s != ",") commaAsDigit = true
            if (!periodAsDigit && s != ".") periodAsDigit = true
        }
        if (beginIdx >= 0) {
            val n = path.size
            if (parser.done()) {
                concat(beginIdx, n)
            } else {
                val ss = path[n - 1].node.wordInfo.normalizedForm
                if ((parser.errorState == NumericParser.Error.COMMA && ss == ",") ||
                    (parser.errorState == NumericParser.Error.POINT && ss == ".")
                ) {
                    concat(beginIdx, n - 1)
                }
            }
        }
        return path
    }

    private fun joinKatakanaOov(rin: HoshiRustInputText, input: List<PathNode>): List<PathNode> {
        val path = input.toMutableList()
        fun isKatakana(p: PathNode): Boolean {
            val (b, e) = charRange(rin, p)
            return rin.catOfCharRange(b, e) and HoshiRustCharCategory.KATAKANA != 0
        }
        fun canOovBow(p: PathNode): Boolean =
            rin.cats[rin.charIndex(p.node.begin)] and HoshiRustCharCategory.NOOOVBOW == 0
        fun isShorter(p: PathNode): Boolean {
            val (b, e) = charRange(rin, p)
            return e - b < katakanaMinLength
        }

        var i = 0
        while (i < path.size) {
            val node = path[i]
            if (!(node.rustOov || isShorter(node)) || !isKatakana(node)) {
                i += 1
                continue
            }
            var begin = i - 1
            while (begin >= 0) {
                if (!isKatakana(path[begin])) {
                    begin += 1
                    break
                }
                begin -= 1
            }
            if (begin < 0) begin = 0
            var end = i + 1
            while (end < path.size && isKatakana(path[end])) end += 1
            while (begin != end && !canOovBow(path[begin])) begin += 1
            if (end - begin > 1) {
                concatOovNodes(path, begin, end, katakanaOovPosId)
                i = begin + 1
            }
            i += 1
        }
        return path
    }
}
