package moe.antimony.hoshi.features.anki.enrich.jrp

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end parity against golden output of the Python `jrp_fill` generator, run over a
 * private corpus. Skipped unless both are set (env var or system property):
 *
 * - `HOSHI_JRP_PARITY_DIR`: directory holding `accent.jsonl`, `gen.jsonl`, `field.jsonl`
 *   produced by the out-of-tree golden generator (`make_golden.py`).
 * - `HOSHI_SUDACHI_DIC`: Sudachi `system.dic` (the sudachidict_full build the goldens used).
 *
 * Optional `HOSHI_JRP_PARITY_MAX_DIFFS` (default 40) caps the diffs listed per section.
 * The report is written to `<parity dir>/report.txt`.
 */
class JrpParityTest {

    @Test
    fun matchesPythonGenerator() {
        val dirPath = setting("HOSHI_JRP_PARITY_DIR")
        val dicPath = setting("HOSHI_SUDACHI_DIC")
        assumeTrue("HOSHI_JRP_PARITY_DIR / HOSHI_SUDACHI_DIC not set", dirPath != null && dicPath != null)
        val dir = File(dirPath!!)
        val dic = File(dicPath!!)
        assumeTrue("parity files missing", File(dir, "gen.jsonl").isFile && dic.isFile)
        val maxDiffs = setting("HOSHI_JRP_PARITY_MAX_DIFFS")?.toIntOrNull() ?: 40

        val acc = ParityAccentIndex.load(File(dir, "accent.jsonl"))
        val tokenizer: JrpTokenizer = SudachiJrpTokenizer.open(dic)
        val gens = File(dir, "gen.jsonl").readLines().filter { it.isNotBlank() }.map { GenCase.parse(it) }
        val fields = File(dir, "field.jsonl").readLines().filter { it.isNotBlank() }.map { FieldCase.parse(it) }

        val morph = Section("M0 tokenizer morphemes (Sudachi mode C + splitA vs SudachiPy)", maxDiffs)
        val unitsSec = Section("L1a units (JrpTokenize.units vs tokenize.units)", maxDiffs)
        val emitSec = Section("L1b emit on golden units (JrpEmit.emitSentence vs emit_sentence)", maxDiffs)
        val genSec = Section("L1c generate end-to-end (emitSentence(units(text)))", maxDiffs)
        val oracleSec = Section("L2a fill with golden generate (JrpSentence.fill logic only)", maxDiffs)
        val fillSec = Section("L2b fill end-to-end (JrpSentence.fill + Kotlin generate)", maxDiffs)
        val unitMismatchWithEqualMorphs = Section("L1a units mismatches where morphemes MATCH (logic bugs)", maxDiffs)
        val unitFieldCounts = sortedMapOf<String, Int>()

        val kotlinGenerate = HashMap<String, String>()
        fun generate(text: String): String = kotlinGenerate.getOrPut(text) {
            JrpEmit.emitSentence(JrpTokenize.units(text, tokenizer, acc))
        }

        for (g in gens) {
            // M0
            val morphDiff = runCatching { morphDiff(g, tokenizer.tokenizeC(g.input)) }
                .getOrElse { "EXCEPTION ${it.stackTraceSummary()}" }
            morph.record(morphDiff == null) { "input: ${g.input.show()}\n$morphDiff" }
            // L1a
            val actualUnits = runCatching { JrpTokenize.units(g.input, tokenizer, acc) }
            val unitDiff = actualUnits.fold(
                onSuccess = { unitDiff(g.units, it) },
                onFailure = { "EXCEPTION ${it.stackTraceSummary()}" },
            )
            unitsSec.record(unitDiff == null) {
                "input: ${g.input.show()}\n$unitDiff" + if (morphDiff != null) "\n(morphemes differ too)" else ""
            }
            if (unitDiff != null && morphDiff == null) {
                unitMismatchWithEqualMorphs.record(false) { "input: ${g.input.show()}\n$unitDiff" }
                actualUnits.getOrNull()?.let { firstDiffFields(g.units, it) }
                    ?.let { unitFieldCounts.merge(it, 1, Int::plus) }
            }
            // L1b
            val emitted = runCatching { JrpEmit.emitSentence(g.units) }.getOrElse { "EXCEPTION ${it.stackTraceSummary()}" }
            emitSec.record(emitted == g.output) { textDiff(g.input, g.output, emitted) }
            // L1c
            val out = actualUnits.map { JrpEmit.emitSentence(it) }.getOrElse { "EXCEPTION ${it.stackTraceSummary()}" }
            kotlinGenerate[g.input] = out
            genSec.record(out == g.output) {
                textDiff(g.input, g.output, out) + (unitDiff?.let { "\nfirst unit diff: $it" } ?: "")
            }
        }

        val goldenGenerate = gens.associate { it.input to it.output }
        val missingOracle = linkedSetOf<String>()
        for (f in fields) {
            val label = "nid=${f.nid} kind=${f.kind}"
            val oracle = runCatching {
                JrpSentence.fill(f.input) { text ->
                    goldenGenerate[text] ?: run { missingOracle += text; generate(text) }
                }
            }.getOrElse { "EXCEPTION ${it.stackTraceSummary()}" }
            oracleSec.record(oracle == f.output) { "$label\n" + textDiff(f.input, f.output, oracle) }
            val full = runCatching { JrpSentence.fill(f.input, ::generate) }
                .getOrElse { "EXCEPTION ${it.stackTraceSummary()}" }
            fillSec.record(full == f.output) { "$label\n" + textDiff(f.input, f.output, full) }
        }

        val sections = listOf(morph, unitsSec, unitMismatchWithEqualMorphs, emitSec, genSec, oracleSec, fillSec)
        val report = buildString {
            appendLine("JRP parity report")
            appendLine("generate inputs: ${gens.size}, field cases: ${fields.size}, accent entries: ${acc.size}")
            appendLine()
            for (s in sections) appendLine("${s.title}: ${s.mismatches} / ${s.total} mismatched")
            appendLine("fill called generate with inputs Python never generated: ${missingOracle.size}")
            if (unitFieldCounts.isNotEmpty()) {
                appendLine("first differing unit fields (morphemes equal): $unitFieldCounts")
            }
            if (missingOracle.isNotEmpty()) {
                appendLine()
                appendLine("== generate inputs unknown to the golden set (first $maxDiffs)")
                missingOracle.take(maxDiffs).forEach { appendLine(it.show()) }
            }
            for (s in sections) {
                if (s.diffs.isEmpty()) continue
                appendLine()
                appendLine("== ${s.title} (first ${s.diffs.size} of ${s.mismatches})")
                s.diffs.forEach { appendLine(it); appendLine("--") }
            }
        }
        File(dir, "report.txt").writeText(report)
        println(report.lineSequence().take(14).joinToString("\n"))

        val failed = sections.filter { it.mismatches > 0 && it !== unitMismatchWithEqualMorphs }
        assertTrue(
            "JRP parity mismatches: " + failed.joinToString { "${it.title.substringBefore(' ')}=${it.mismatches}" } +
                " (missing oracle=${missingOracle.size}); see ${File(dir, "report.txt")}",
            failed.isEmpty() && missingOracle.isEmpty(),
        )
    }

    // ---- diffing -------------------------------------------------------------------------

    private class Section(val title: String, private val maxDiffs: Int) {
        var total = 0
        var mismatches = 0
        val diffs = mutableListOf<String>()
        fun record(ok: Boolean, describe: () -> String) {
            total++
            if (ok) return
            mismatches++
            if (diffs.size < maxDiffs) diffs += describe()
        }
    }

    private fun morphDiff(g: GenCase, actual: List<JrpMorpheme>): String? {
        val exp = g.morphs
        val act = actual.map { m ->
            val splits = m.splitA()
            GoldenMorph(
                m.surface(), m.partOfSpeech(), m.dictionaryForm(), m.readingForm(),
                // SudachiPy 0.6 returns [] for an unsplittable morpheme; compare split-or-not.
                if (splits.size <= 1) emptyList() else splits.map { it.surface() to it.readingForm() },
            )
        }
        val n = minOf(exp.size, act.size)
        for (i in 0 until n) {
            if (exp[i] != act[i]) return "morpheme #$i\n  py: ${exp[i]}\n  kt: ${act[i]}" + context(exp, act, i)
        }
        if (exp.size != act.size) return "morpheme count py=${exp.size} kt=${act.size}" + context(exp, act, n)
        return null
    }

    private fun unitDiff(exp: List<JrpUnit>, act: List<JrpUnit>): String? {
        val n = minOf(exp.size, act.size)
        for (i in 0 until n) {
            if (exp[i] != act[i]) return "unit #$i\n  py: ${exp[i]}\n  kt: ${act[i]}" + context(exp, act, i)
        }
        if (exp.size != act.size) return "unit count py=${exp.size} kt=${act.size}" + context(exp, act, n)
        return null
    }

    private fun firstDiffFields(exp: List<JrpUnit>, act: List<JrpUnit>): String {
        val i = exp.indices.firstOrNull { it >= act.size || exp[it] != act[it] } ?: return "count"
        if (i >= act.size) return "count"
        val e = exp[i]
        val a = act[i]
        return buildList {
            if (e.surface != a.surface) add("surface")
            if (e.lemma != a.lemma) add("lemma")
            if (e.reading != a.reading) add("reading")
            if (e.lemmaReading != a.lemmaReading) add("lemmaReading")
            if (e.isContent != a.isContent) add("isContent")
            if (e.isYougen != a.isYougen) add("isYougen")
            if (e.accents != a.accents) add("accents")
            if (e.ambiguous != a.ambiguous) add("ambiguous")
            if (e.conjComputed != a.conjComputed) add("conjComputed")
        }.joinToString("+")
    }

    private fun <T> context(exp: List<T>, act: List<T>, i: Int): String {
        val from = maxOf(0, i - 1)
        return "\n  py[$from..]: ${exp.drop(from).take(4)}\n  kt[$from..]: ${act.drop(from).take(4)}"
    }

    private fun textDiff(input: String, expected: String, actual: String): String {
        val p = expected.commonPrefixWith(actual).length
        return "input:    ${input.show()}\nexpected: ${expected.show()}\nactual:   ${actual.show()}\n" +
            "first difference at char $p: py=${expected.drop(p).take(30).show()} kt=${actual.drop(p).take(30).show()}"
    }

    private fun String.show(): String = buildString {
        for (c in this@show) {
            when {
                c == '\n' -> append("\\n")
                c == '\t' -> append("\\t")
                c.code < 0x20 || c.code in 0x200B..0x200F || c.code in 0x202A..0x202E || c.code == 0xFEFF ->
                    append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }

    private fun Throwable.stackTraceSummary(): String =
        toString() + stackTrace.take(6).joinToString("") { "\n    at $it" }

    private fun setting(name: String): String? =
        System.getProperty(name)?.takeIf { it.isNotBlank() } ?: System.getenv(name)?.takeIf { it.isNotBlank() }

    // ---- golden data ---------------------------------------------------------------------

    private data class GoldenMorph(
        val surface: String,
        val pos: List<String>,
        val dictionaryForm: String,
        val readingForm: String,
        val splitA: List<Pair<String, String>>,
    )

    private class GenCase(
        val input: String,
        val output: String,
        val units: List<JrpUnit>,
        val morphs: List<GoldenMorph>,
    ) {
        companion object {
            fun parse(line: String): GenCase {
                val o = Json.parseToJsonElement(line).jsonObject
                return GenCase(
                    input = o.str("in"),
                    output = o.str("out"),
                    units = o.arr("units").map { u ->
                        val a = u.jsonArray
                        JrpUnit(
                            surface = a[0].s,
                            lemma = a[1].s,
                            reading = a[2].s,
                            lemmaReading = a[3].s,
                            isContent = a[4].jsonPrimitive.boolean,
                            isYougen = a[5].jsonPrimitive.boolean,
                            accents = a[6].jsonArray.map { it.jsonPrimitive.int },
                            ambiguous = a[7].jsonPrimitive.boolean,
                            conjComputed = a[8].jsonPrimitive.boolean,
                        )
                    },
                    morphs = o.arr("morphs").map { m ->
                        val a = m.jsonArray
                        GoldenMorph(
                            a[0].s,
                            a[1].jsonArray.map { it.s },
                            a[2].s,
                            a[3].s,
                            a[4].jsonArray.map { it.jsonArray[0].s to it.jsonArray[1].s }
                                .let { if (it.size <= 1) emptyList() else it },
                        )
                    },
                )
            }
        }
    }

    private class FieldCase(val nid: Long, val kind: String, val input: String, val output: String) {
        companion object {
            fun parse(line: String): FieldCase {
                val o = Json.parseToJsonElement(line).jsonObject
                return FieldCase(o["nid"]!!.jsonPrimitive.long, o.str("kind"), o.str("in"), o.str("out"))
            }
        }
    }

    /**
     * Test-local `accent.py` `AccentIndex` over the exported table (insertion order kept), so
     * the harness does not depend on the production implementation.
     */
    private class ParityAccentIndex(private val table: LinkedHashMap<String, List<Int>>) : JrpAccentIndex {
        val size get() = table.size

        private val rev: Map<String, Map<String, List<Int>>> by lazy {
            val r = HashMap<String, LinkedHashMap<String, List<Int>>>()
            for ((key, positions) in table) {
                val (lem, rd) = partition(key)
                r.getOrPut(lem) { LinkedHashMap() }[rd] = positions
            }
            r
        }

        private val rread: Map<String, List<Int>> by lazy {
            val seen = HashMap<String, MutableSet<Set<Int>>>()
            val first = LinkedHashMap<String, List<Int>>()
            for ((key, positions) in table) {
                val rd = partition(key).second
                seen.getOrPut(rd) { HashSet() }.add(positions.toSet())
                first.putIfAbsent(rd, positions)
            }
            first.filterKeys { seen.getValue(it).size == 1 }
        }

        override fun lookup(lemma: String, readingHira: String): List<Int>? = table[lemma + "\t" + readingHira]

        override fun readingsFor(lemma: String): Map<String, List<Int>> = rev[lemma] ?: emptyMap()

        override fun byReading(readingHira: String): List<Int>? = rread[readingHira]

        companion object {
            /** Python `str.partition("\t")`. */
            private fun partition(key: String): Pair<String, String> {
                val i = key.indexOf('\t')
                return if (i < 0) key to "" else key.substring(0, i) to key.substring(i + 1)
            }

            fun load(file: File): ParityAccentIndex {
                val table = LinkedHashMap<String, List<Int>>(400_000)
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val a = Json.parseToJsonElement(line).jsonArray
                    table[a[0].s] = a[1].jsonArray.map { it.jsonPrimitive.int }
                }
                return ParityAccentIndex(table)
            }
        }
    }
}

private val JsonElement.s: String get() = jsonPrimitive.content
private fun JsonObject.str(key: String): String = getValue(key).s
private fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray
