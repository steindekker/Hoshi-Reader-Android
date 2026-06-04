# Sentence enrichment pipeline (furigana + pitch + cloze) — research brief

**Scope note.** Pitch generation and cloze generation are **one bundled pass**, because both derive from the same tokenized parse of the sentence. Tokenize once → fill per-word furigana + pitch (→ marked Sentence) AND locate the target token by lemma (→ Cloze Before/Inside/After). Bundling makes cloze's hard case free: the ~19% of targets that don't match the sentence verbatim (conjugated forms) become findable via the tokenizer's dictionary-form output, instead of brittle substring search. Cloze-coverage is therefore a metric this spike should also report (verbatim baseline = 81%; target-lemma alignment should raise it).

**Why this matters.** AnkiAlign goal #2 is "add pitch accent to the sentence where there is none yet." Measured against the real collection (`collection.anki2`, schema v18), the gap is:

- **~1,780 plain-text sentences** with no markup, furigana, or pitch — **1,751 in jidoujisho Kinomoto** (the conversion target) + 29 in MyNote. These need the full pipeline: tokenize → reading → furigana → pitch → emit jrp markup.
- **~1,500 marked sentences** with bare kanji sitting *outside* `{unit}` blocks (no furigana/pitch) — need partial fill.
- **~31,000 already-marked `{unit;accent}` blocks are 100% pitched already** (MyNote 30022/30022, Yuuna 648/648, Kinomoto 404/404) — leave untouched.

Card quality depends entirely on producing the right **(word boundary, reading, accent)** triple per word. The rendering is already solved: the Kinomoto template embeds the JRP (Japanese Readings & Pitch) renderer, which draws pitch graphs from `{unit;accent}` markup (accent = downstep mora int, or Migaku `[hkano]\d*`; `;Y…` marks 用言/yougen; renderer applies conjugation shift given dictionary-form accent + is_yougen). We only need to *fill the codes*.

The decision this spike must settle: **(a) which pitch dictionary, and (b) how far down the accuracy tail to chase** (dict-only vs +neural vs +OJAD). Decide from data, not guesses.

**Free ground-truth set.** The ~31k already-pitched units were accented by jidoujisho's source dictionary and accepted by the user. Extract `(base form, reading, accent)` from each and treat as gold. Any candidate dictionary's accuracy = how often its lookup on `(base, reading)` reproduces the gold accent. This is the `validate_jrp_readings` methodology applied to pitch.

**Candidates to investigate.** Include but don't limit to:

*Pitch dictionaries (data sources)*
- **NHK 日本語発音アクセント新辞典** in Yomitan `pitch` term-meta format — expected SOTA / most authoritative.
- **大辞泉 (Daijisen)** — user owns `dicts/daijisen-2.zip`; carries sparse `アクセント` markers in definitions (not structured pitch term-meta) — assess whether usable as a secondary.
- **Kanjium / "Yomichan pitch accents" TSV** — community merge, partly NHK-derived; baseline to compare coverage/accuracy against NHK.
- Any layering strategy (NHK primary → Daijisen/Kanjium fallback).

*Tokenizer for plain sentences (boundary + reading + dict form + yougen)*
- **Reuse perapera's validated pipeline** (`SudachiPy` Mode A + JMdict/Jitendex dict-aware lemma layer) — already 97.6% exact (expr,reading) / 99.9% coverage on these exact decks. Primary candidate; confirm it runs inside Anki's bundled Python (or as a shared lib / sidecar).
- For already-marked sentences, **trust the embedded reading** in the markup — do not re-tokenize.

*Accuracy tail (words missing from the pitch dict)*
- **`marine`** — offline neural accent estimator (fallback for dict misses).
- **OJAD Suzuki-kun** — gold-standard whole-sentence accenting, but online service (ToS, rate limits) — small-sample reference only, not a bulk dependency.

**Tests — actually run them.**

1. **Ground-truth accent accuracy.** Parse the 31k units (port the jrp parser from the template JS in `/tmp/kino_*.html`). For each unit extract `(base, reading, accent, is_yougen)`. Run each candidate dict's `(base, reading)` lookup, compare predicted dictionary-form accent to gold. Report per dict and layered: `exact_match`, `mismatch` (and top mismatches, like the reading audit), `not_found`. Handle yougen by comparing dictionary-form accent.

2. **Plain-sentence coverage.** Take the 1,751 Kinomoto + 29 MyNote plain sentences. Run tokenize → reading → furigana → pitch lookup. Report: % tokens with confident reading, % with accent found, % unknown; spot-check 25 rendered results for correctness (furigana right? accent right? segmentation sane?).

3. **Tail recovery.** For the `not_found` set from tests 1–2, measure how much `marine` recovers and (≤25-sentence sample) how OJAD compares. Quantifies the marginal value of each tier.

**Also capture (per dict/tool):** version, release date, maintenance status; coverage size (entries); license (name only); reading-disambiguation behavior; install/disk footprint *inside Anki's bundled Python* (matters — add-on, not free-standing venv); known gotchas (counters, proper names, compound nouns, kana-only words, conjugated yougen).

**Deliverable.** `REPORT.md` with:
- Accuracy table from test 1 (rows = dict/layering, cols = exact/mismatch/not_found %) + a top-mismatches list.
- Coverage table from test 2 + spot-check sample.
- Tail-recovery numbers from test 3.
- A **verdict**: recommended pitch dictionary (+ layering), and a recommended accuracy tier (dict-only / +marine / +OJAD) with the data that justifies it.
- Open questions for the human (e.g. how to render `?`/unknown words; whether to fill bare-kanji-outside-units; dependency strategy for Sudachi inside an add-on).
