# AnkiAlign — JRP sentence-markup generation (design)

Date: 2026-06-04. Status: approved in brainstorming, ready for implementation plan.
Companion research: `research/pitch-pipeline/REPORT-jrp-sentence-generation.md` (build-vs-buy),
`research/pitch-pipeline/REPORT-pitch-dicts.md` (dict choice), `research/pitch-pipeline/BRIEF.md`
(pipeline brief), `research/jrp-pipeline/work/spike_segment*.py` (segmentation/accent spike).

## Goal

Backfill jidoujisho/JRP `{unit;accent}` markup into Sentence fields that lack it, so the Kinomoto
template's embedded JRP renderer draws furigana + pitch on sentences that currently render flat.

Measured gap (real collection, 2026-06-04): **1,751 of 1,841** non-empty `jidoujisho Kinomoto`
Sentence fields are plain (no markup); ~1,500 more are *partially* marked (bare kanji outside
existing `{}` blocks). The ~31k already-`{…}` blocks are fully pitched — **leave untouched**.

## Why these choices (settled in brainstorming)

- **Build our own generator, don't adopt anki-jrp as the runtime tool.** Its NHK+Kanjium accent data
  covers only ~85% of the user's mined gold vs アクセント辞典v2's 97.5%; it's GUI-bound, MeCab-based,
  and a second accent authority that disagrees with the term-level SVGs. We *do* port anki-jrp's
  MIT-licensed emit/conjugation logic (the hard math) rather than rewriting it. See build-vs-buy report.
- **Standalone offline `uv` CLI, run with Anki closed.** `sudachidict-full` is a heavy native
  dependency that's painful to ship inside Anki's bundled Python. The backfill is a batch over
  existing cards (not live mining), so it doesn't need to live in the add-on — it mirrors the repo's
  existing offline scripts (`validate.py`, `build_index.py`) and the add-on's dry-run→backup→write
  safety pattern.
- **SudachiPy Mode C for segmentation** (not perapera's Mode A, which deliberately over-splits for
  vocab extraction). Mode C is the long-unit/compound mode — 主義者, 説き伏せる, 踏み入れる, 異世界
  come out as single units. perapera is a POC and is used only as a *reference to read*, not a dep.
- **Cloze is out of scope** — 1,749/1,751 plain sentences already have Cloze Before/Inside/After
  populated by jidoujisho. `SentenceFurigana` is also out of scope — the template renders `{{Sentence}}`,
  not that field.

## Empirical validation (the spike — this is the quality bar)

200-sentence / 968-content-unit sample of the real plain sentences, accent sources = user gold ∪
アクセント辞典v2 ∪ NHK2016:

| mechanism | strict (term,reading) hit |
|---|--:|
| Mode C + surface-reading lookup (naive) | 71.5% |
| + **dictionary-form reading lookup** | 98.0% |
| + **Mode C → Mode A split-and-retry** | **98.7%** |
| true miss → marine fallback | **1.2%** |

The split cascade cleanly fixed every over-merge observed (前途多難→前途+多難, 寝る時間→寝る+時間,
チャイナドレス→チャイナ+ドレス, 誹謗中傷→誹謗+中傷, 頭角を現す→頭角+現す). Residual misses are
colloquialisms (みたい, ねぇ), katakana loans (ボンクラ, グール), and split yojijukugo halves — exactly
marine's job, else render furigana only.

**Accent role decided by data:** curated dicts cover 98.7% *exactly*; using marine as primary would
replace human-curated accents with neural estimates for that 98.7% (lower quality). So **dict-primary,
marine fallback for the 1.2%.**

## JRP syntax we emit (default dialect, matches the template + `ankialign/markup.py`)

- reading: `[漢字|かな]` per kanji run; kana left bare.
- pitched unit: `{[受|う]け[入|い]れる;Y4,0}` — `;` then comma-separated downstep morae (`0`=平板),
  `!`=ambiguous reading, `Y`=用言 (renderer applies the conjugation shift from the dict-form accent).
- Multiple dict positions emitted comma-separated; ambiguity marked `!` — faithful to the gold style.

## Architecture (pure transforms vs heavy-dep vs collection I/O)

```
ankialign/jrp_fill/                    # offline-only package (NOT shipped in the add-on)
  align.py       pure: (surface, reading) -> [(kanji|kana) | bare-kana] furigana segments
  emit.py        pure: Unit list -> "{[k|y];Yacc}" JRP string (anki-jrp algorithm, ported)
  deinflect.py   pure: small suffix-stripper fallback (ported from anki-jrp/perapera)
  splice.py      pure: merge new units into a partially-marked sentence, never touching {} blocks
  accent.py      AccentIndex.lookup(lemma, reading) -> (positions, is_yougen, ambiguous)
                 simple lookup over the prebuilt index (source priority baked in at build time)
  tokenize.py    Sudachi mode-C singleton + lemma-reading + Mode C->A split cascade -> Unit list
  marine_tail.py marine fallback for accent misses (lazy import; heavy PyTorch dep)
  engine.py      sentence -> markup; plan/diff over an opened anki Collection
  cli.py         `uv run python -m ankialign.jrp_fill --dry-run | --apply` ; backup on apply

ankialign/data/
  accent_index.marshal.gz              # shipped: (lemma,reading)->positions(+yougen) from v2+NHK2016+gold

research/jrp-pipeline/
  build_accent_index.py                # offline one-shot: dicts + gold -> accent_index
  work/spike_segment*.py               # the validation spike (kept as regression reference)
```

Pure modules (`align`, `emit`, `deinflect`, `splice`) import only stdlib and are exhaustively unit-
tested. `tokenize`/`accent`/`marine_tail` own the heavy deps behind a narrow interface. `engine`/`cli`
are the only collection-touching code. This mirrors the existing `markup.py` (pure) / `pitch.py`
(anki) / `preview.py` (aqt) split.

**Collection I/O uses the `anki` Python library, not raw sqlite writes.** Direct sqlite edits to
`notes.flds` would skip Anki's note `mod`/`usn` bumps and field checksum (`csum`/`sfld`) maintenance,
corrupting sync and dupe-detection. The CLI opens the closed collection via `anki.collection.Collection`
in the uv env and uses `col.get_note` / `col.update_notes`, so `engine` takes an opened `col` — the
same signature shape as the existing `pitch.build_pitch_plan(col)`. (Planning may still read fields
directly; only writes must go through `col`.)

### Unit (the interface between tokenize and emit)

```
Unit = {
  surface: str          # text as it appears (may be inflected): 見逃し
  lemma: str            # dictionary form: 見逃す
  reading: str          # hiragana reading of the surface: みのがし
  lemma_reading: str    # hiragana reading of the lemma (accent key): みのがす
  is_content: bool      # False for particles/aux/punctuation -> emitted as bare text
  is_yougen: bool       # 動詞/形容詞 -> emit ;Y
  accents: list[int]    # resolved downstep positions ([] = no pitch block, furigana only)
  ambiguous: bool       # reading-ambiguous -> emit !
}
```

## Data flow

1. `engine.plan(col)` selects target notes: `jidoujisho Kinomoto` (extensible to MyNote/Yuuna),
   Sentence non-empty, classified **plain** (no `{…;…}`) or **partial** (has `{}` but bare kanji
   outside). Already-fully-marked → skipped.
2. Per sentence: strip `<br>` to spaces for tokenization, preserve them for re-insertion.
3. `tokenize.units(text)` → `[Unit]`: Mode C; per content morpheme look up accent via
   `accent.lookup(lemma, lemma_reading)`; on miss, `morpheme.split(A)` and resolve sub-units; on
   still-miss, `marine_tail` (if enabled), else `accents=[]`.
4. `align` builds furigana segments; `emit` produces the JRP string.
5. **Plain** sentence → emitted string replaces Sentence. **Partial** → `splice` fills only the
   bare-kanji spans, leaving existing `{}` blocks byte-for-byte intact.
6. **Round-trip guard:** `markup.jrp_surface(emitted)` must equal the original visible surface
   (whitespace/`<br>` normalized). Mismatch → skip the note, log it, never write.
7. `--dry-run` prints counts + a diff sample (no collection open for write). `--apply` copies
   `collection.anki2` to a timestamped backup, opens it via the `anki` library, and writes through
   `col.update_notes` (same forced-backup-first principle as the unify/pitch flows).

## Accent index (`build_accent_index.py`, offline one-shot)

Reuses `validate.load_dict` (Yomitan `term_meta_bank` pitch reader) + `validate.extract_gold` (the
JRP gold parser). Output keyed `(lemma, hiragana-reading) -> {positions, is_yougen}` with source
priority **gold > アクセント辞典v2 > NHK2016** (gold wins so cards stay consistent with the user's
accepted accents). **[Superseded 2026-06-05 — see `2026-06-04-pitch-graph-backfill-design.md`:
priority flipped to dict-first (gold → NHK2016 → v2) so the dictionaries are authoritative and
unverified jidoujisho auto-gold no longer overrides them; gold now only fills dict-miss words.
Key set unchanged, so JRP coverage is unaffected.]** Shipped compact (marshal+gz). Rebuilt only
when dicts change.

## Scope, selection, edge cases

- **In:** plain + partial `jidoujisho Kinomoto` Sentence fields.
- **Splice safety:** existing `{}` blocks are never re-tokenized or modified. Visual grouping of newly
  filled units may differ slightly from jidoujisho's original style within a partial sentence —
  accepted.
- **No accent found:** emit `[漢字|かな]` furigana, no `{…}` pitch block (never fabricate).
- **marine:** opt-in flag; off → misses get furigana only. On → PyTorch in the uv env only.
- **Anki must be closed** (collection lock); CLI refuses to run against a locked DB.
- **Idempotent:** re-running skips already-fully-marked sentences; safe after each unify pass.
- **Other note types** (MyNote 29 plain, Yuuna) reachable by config (field name per note type);
  default run targets Kinomoto since unify migrates everything there.

## Testing — ground truth is the lever

- **Pure unit tests** (exact-string): `align` (okurigana, mixed kana/kanji, multi-run),
  `emit` (single/multiple/ambiguous accents, `;Y` yougen, `0` 平板), `deinflect`, `splice`
  (fill-around-existing, idempotence, no-touch of `{}`).
- **Round-trip property test:** for the gold sentences, `jrp_surface(emit(parse(...)))` == surface.
- **Ground-truth regression harness:** re-run the spike (`spike_segment2.py` logic) as a pytest gate —
  strict (term,reading) coverage on the 200-sentence sample must stay **≥ 98%**; alerts on dict/
  tokenizer regressions. Skipped when `collection.anki2` absent (same pattern as existing groundtruth tests).
- **25-sentence rendered spot-check** committed for human review of furigana + accent correctness.

## Deliverables

- `ankialign/jrp_fill/` package (modules above) + `ankialign/data/accent_index.*.gz`.
- `research/jrp-pipeline/build_accent_index.py` (offline builder) + retained spike scripts.
- Tests: `tests/test_jrp_align.py`, `test_jrp_emit.py`, `test_jrp_splice.py`, `test_jrp_deinflect.py`,
  `test_jrp_groundtruth.py`.
- `uv` dependency group for the offline tool: `anki` (collection I/O), `sudachipy`,
  `sudachidict-full`, (optional) `marine` / `marine-plus`.

## As-built notes (implemented on `feat/jrp-sentence-fill`, 2026-06-04/05)

- **代名詞 (pronouns) are content** (彼/彼女/誰 get furigana + pitch) — a final-review fix; cut
  bare-kanji sentences from 55/200 → 8/200 with zero wrong readings.
- **接尾辞 (counters) deliberately stay bare.** Empirically, making them content introduced *wrong*
  readings on irregular number compounds (二日 → ふたか instead of ふつか; risk to 一人/二人). A
  bare-but-correct kanji beats a wrong furigana. A bare-kanji regression test guards the rate (≤15/200).
- **deinflect.py omitted (YAGNI).** Sudachi `dictionary_form()` + lemma-reading + the Mode C→A split
  cascade reached 98.9% strict coverage without a hand-rolled deinflector.
- **marine dropped.** Built then removed: measured on the real dict-misses, marine returned heiban
  `(0,)` for 9/10 (and None for 1) — zero useful predictions, because it's trained on full-sentence
  sequences and gets fed isolated words. The misses are mostly function words / slang / loans that
  don't need a pitch graph. Revisit only via *sentence-level* marine (feed the whole sentence, map
  accent phrases back to units) if the last ~1% ever matters. Dict-misses now render furigana-only.
- **`!` ambiguity mark deferred** — `emit` supports the syntax but nothing sets `Unit.ambiguous` yet.
- **Idempotency caveat:** the first `--apply` is safe (surface round-trip guard + forced backup). A
  *second* run can re-tokenize a leftover bare-counter kanji in isolation and write a different
  (possibly wrong) reading that the surface-only guard won't catch (~4% of sentences). Treat as a
  **one-time backfill**; don't re-run without reviewing. Full idempotency would need a
  "fully-marked → skip" classifier (deferred).
- Final strict-coverage gate: **98.96%** (1044/1055) on the 200-sentence seed-7 sample.

## Open / deferred

- **marine integration depth** — wire as per-miss fallback first; whether to batch whole sentences
  through marine for speed is an implementation detail, not a quality lever (dicts cover 98.7%).
- **marine-plus vs marine** — `tsukumijima/marine-plus` adds Windows support; pick at impl time.
- **Partial-sentence visual consistency** — if spot-checks show jarring grouping mismatches, consider
  re-emitting the whole partial sentence instead of splicing (deferred; splice is the safer default).
- **Promotion to an in-Anki action** — only if a live (non-batch) need appears; would require solving
  Sudachi bundling. Not planned.
