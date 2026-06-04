# AnkiAlign — Term pitch-graph backfill (design)

**Date:** 2026-06-04
**Status:** approved (brainstorming) → ready for implementation plan
**Scope:** Goal #2, term-level only — fill the **`Pitch Accent` field** (the mined word's
SVG pitch graph) on Kinomoto notes that lack it. **Out of scope:** sentence inline pitch
(`{unit;accent}` in the Sentence field) — handled by a separate third-party JRP add-on.

## Goal

For `jidoujisho Kinomoto` notes whose `Pitch Accent` field is empty, generate a
jidoujisho-style **self-contained SVG** pitch graph from `(Term, Reading)`, looked up in a
single dictionary — **アクセント辞典v2** — using the **primary accent only**. The pass is
idempotent, runs behind a forced backup + dry-run preview, and **never overwrites** an
existing graph.

## Why these choices (settled in brainstorming)

- **One dict, アクセント辞典v2.** Validation against the user's own 10,966 mined pitch units
  showed 97.5% coverage at accuracy indistinguishable from NHK; it is a near-superset of
  every other dict in the pack. See `research/pitch-pipeline/REPORT-pitch-dicts.md`.
- **Self-contained SVG** (not markup + template JS): backfilled cards stay pixel-consistent
  with the 2,256 cards that already carry jidoujisho's SVG, and render everywhere
  (AnkiMobile/AnkiDroid, browser, plain exports).
- **Primary accent only.** アクセント辞典v2 stores no usage frequency, only position order;
  `pitches[0]` is the primary/most-common. Existing cards show a single graph each.
- **No tokenizer.** Term + Reading are already on the card, so backfill is a single
  `(term, reading)` lookup. (Inflected/sentence forms are the other add-on's problem.)

## SVG format (reverse-engineered from the 2,256 existing graphs)

Standard kanjium/jidoujisho pitch SVG. For an `n`-mora reading + following particle slot:
- **Mora text:** `<text x=5+35·i y=67.5 font-size:20px>` per mora glyph.
- **Contour points:** mora `i` center at `x=16+35·i`; particle at `x=16+35·n`.
  `y=5` (high) / `y=30` (low). Heights follow the accent pattern:
  - heiban (0): mora0 low, all others high incl. particle.
  - atamadaka (1): mora0 high, all others low.
  - nakadaka/odaka (k≥2): mora0 low, moras 1..k-1 high, downstep after mora k → low.
- **Circles:** `r=5` filled (`currentColor`) on each mora point; the particle point is a
  `r=4` **hollow** circle (`stroke=currentColor`, no fill).
- **Paths:** straight `<path>` segments connecting consecutive points.
- **viewBox** `0 0 (16+35·n+16) 75`; rendered `width = vbWidth·0.6 px`, `height = 45px`.
- **No devoicing/nasal markers.** アクセント辞典v2 carries zero devoice/nasal data (0 of
  203k pitches) and existing SVGs use plain circles — so none are emitted.

Mora segmentation uses the template's `split_moras` rules (combine small kana ゃゅょ/ァ-ォ
with the preceding mora; ー and っ are their own moras), ported verbatim.

## Architecture (mirrors existing module boundaries: pure transforms vs anki vs aqt)

| Unit | Purpose | Depends on |
|---|---|---|
| `ankialign/pitch_svg.py` | **Pure.** `render_svg(reading:str, accent:int) -> str` + `split_moras`. The SVG generator. | nothing (stdlib) |
| `ankialign/pitch_index.py` | **Pure.** `PitchIndex.load(path)`, `.lookup(term, reading) -> int | None`. hira/kata normalization; `(Term,Reading)` then kana-headword `(Reading,Reading)` fallback. | data file only |
| `ankialign/data/pitch_index.*` | Prebuilt compact index `(expr, reading) → primary position`, ~327k keys, gzipped. Shipped in the add-on, loaded once per pass. | — |
| `research/pitch-pipeline/build_index.py` | **Offline.** アクセント辞典v2 `term_meta` → `pitch_index.*`. Run once; output committed. | source dict |
| `ankialign/pitch.py` | Engine: `build_pitch_plan(col)` (read-only counts+samples) and `backfill_pitch(col) -> {filled, not_found, skipped}`. Idempotent, like `normalize_media`. | anki, the three units above |
| GUI in `preview.py` / `__init__.py` | Tools action “AnkiAlign: Backfill pitch graphs…”, dry-run dialog, optional tail-call from the unify run. | aqt |

**Data flow:** `Kinomoto note → (Term, Reading) → pitch_index.lookup → position →
pitch_svg.render_svg → write Pitch Accent`.

**Index packaging:** build script emits a `dict[(expr,reading)→int]` serialized with
stdlib `marshal` + gzip (compact, fast, no third-party dep). Loaded fully into memory for
the batch pass (one-shot, not per-review, so memory is a non-issue). Source dict for the
build lives at an **absolute path in the main repo** (gitignored, not in the worktree):
`research/pitch-pipeline/dicts/d/アクセント辞典v2/term_meta_bank_*.json`.

## Scope, selection, and edge cases

- **In scope:** `note:"jidoujisho Kinomoto"`, `Pitch Accent` empty, `Reading` non-empty.
- **Never overwrite** a non-empty `Pitch Accent` (the 2,256 jidoujisho graphs and any
  prior run). Idempotent re-runs are no-ops.
- **Not found in dict** (~2.5%): leave empty, count `not_found`. No fabrication.
- **Missing `Reading`** (~81 notes): skip, count `skipped`.
- **Multi-accent:** render `pitches[0]` (primary).
- **Yougen:** the term is the dictionary headword, so the dict-form accent applies
  directly — no conjugation logic here.
- **Katakana readings:** rendered as-is (glyphs taken from the actual `Reading`), matching
  existing cards; normalization is only for the lookup key.
- Runs after a **forced backup** (reuse `convert.backup_collection`) and only after a
  dry-run preview, consistent with the unify flow. Writes only changed notes.

## Testing — ground truth is the lever

1. **Ground-truth SVG match (primary test).** The 2,256 existing `Pitch Accent` SVGs are
   jidoujisho's own output. For each, look up `(Term, Reading)` in the index, render with
   `pitch_svg`, and compare to the stored SVG. A high exact-match rate validates **both**
   the SVG generator and the dict lookup in one shot (same methodology as the project's
   reading/pitch audits). Triage mismatches into: generator bug, dict disagreement, or
   reading mismatch. Target: generator reproduces jidoujisho's SVG for the overwhelming
   majority; remaining gaps are dict-coverage, not generator, defects.
   - Fixture: read the collection by **absolute path** `~/projects/anki-align/collection.anki2`
     (gitignored — not present in the worktree). Test skips gracefully if absent.
2. **Unit tests** for `split_moras` (ゃゅょ, っ, ー, katakana) and `render_svg` across the
   four patterns (heiban / atamadaka / nakadaka / odaka), incl. 1-mora and long readings;
   assert exact SVG strings for a few hand-checked cases.
3. **Lookup tests** for hira/kata normalization and kana-headword fallback.
4. **Idempotency test:** second `backfill_pitch` run fills 0.

## Deliverables

- New modules `pitch_svg.py`, `pitch_index.py`, `pitch.py`; data `pitch_index.*`;
  `research/pitch-pipeline/build_index.py`; GUI wiring; tests incl. the ground-truth audit.
- Backfill report (filled / not_found / skipped) surfaced in the preview dialog.

## Open / deferred

- Sentence inline pitch — explicitly deferred to the third-party JRP add-on.
- Re-running after the unify conversion (Kanji Study → Kinomoto adds pitch-less notes): the
  standalone pass covers them; the unify tail-call is a convenience, not required.
