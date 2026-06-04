# AnkiAlign — Unify note types (design)

**Date:** 2026-06-04
**Status:** approved (brainstorming) → ready for implementation plan
**Scope:** Goal #1 only — unify Japanese vocab notes onto a single note type. Pitch,
audio, and cloze generation are **separate later features**; this milestone only moves
data so those features have one schema to fill.

## Goal

Convert the in-scope vocab notes of three source note types onto the existing
**jidoujisho Kinomoto** note type, **preserving all card review/scheduling history**,
non-destructively (forced backup + dry-run preview + single undoable op).

## Platform & mechanism

In-process Anki add-on (`anki`/`aqt` APIs) — the only path that preserves scheduling
history. Conversion uses **`Collection.models.change(notetype, nids, new_notetype,
fmap, cmap)`** (approach A): it carries cards + history across with template map
`{0:0}`. Because `change()` only *copies* fields and several mappings are *transforms*,
the engine **snapshots each note's source fields before `change()`**, then a
deterministic post-pass repopulates Kinomoto fields from the snapshot. Each mapping is
therefore a pure `dict → dict` function (unit-testable); `change()` is just the
history-preserving vehicle.

Rejected: pure `change()` (leaves raw markup in Term, empty Reading/Furigana — worse
than today); manual rebuild (destroys history — dealbreaker).

## Scope (deck-level, not note-type-level)

Convert notes of **MyNote**, **jidoujisho Yuuna**, **Kanji Study Word Model v3** whose
card sits in an **in-scope mined deck**. In-scope = deck name does **not** contain
`Core2.3k` or `文法`.

- MyNote: **5,022** in-scope (excludes 言葉Core2.3k 2,235 + 文法 2; those stay on MyNote).
- Yuuna: **211** (all in-scope) → Yuuna left empty after.
- Kanji Study: **259** (all in-scope) → KS left empty after.
- **Untouched:** already-Kinomoto notes (decks Default 1,712 / 言葉読みなし 776 / 言葉文章
  193 — they're the target; enrichment is the later feature); RRTK/MIA Kanji; 文法
  note type; the premade Core2.3k deck.

Selection is per-note via its card's deck. A note with cards spanning in- and
out-of-scope decks → convert if any card is in-scope (rare for these 1-card vocab notes;
flagged in preview).

## Per-source field mapping

Kinomoto target fields: Term, Reading, Furigana, Sentence, SentenceFurigana, Cloze
Before, Cloze Inside, Cloze After, Meaning, Expanded Meaning, Collapsed Meaning, Notes,
Context, Frequency, Pitch Accent, Image, Term Audio, Sentence Audio.

`surface/reading/furigana(...)` are `markup.py` derivations (jrp markup → surface text /
kana reading / Anki ` 漢字[かな]` notation).

### MyNote → Kinomoto (5,022)

| Kinomoto | From |
|---|---|
| Term | `surface(Target Word)` |
| Reading | `reading(Target Word)` |
| Furigana | `furigana(Target Word)` |
| Sentence | `Sentence` (as-is jrp markup) |
| Meaning | `Definitions` |
| Pitch Accent | `Pitch` |
| Image | `Screenshot`, else `Images` |
| Term Audio | `Word Audio` |
| Sentence Audio | `Sentence Audio` |
| Frequency | `Frequency` |
| **dropped** | `Translation` (4% / 204 notes, not displayed), `Example Sentences` (11% / 569, not displayed) |

### jidoujisho Yuuna → Kinomoto (211)

Field names already align with Kinomoto; only `Term` needs cleaning.

| Kinomoto | From |
|---|---|
| Term | `surface(Term)` (strip stray jrp markup) |
| Reading, Furigana | `Reading`, `Furigana` (as-is) |
| Sentence, Meaning, Notes, Image, Term Audio, Pitch Accent, Expanded Meaning, Collapsed Meaning, Sentence Audio | same-named (as-is) |
| **dropped** | none |

### Kanji Study Word Model v3 → Kinomoto (259, core fields only)

| Kinomoto | From |
|---|---|
| Term | `surface(EntryFurigana)` → e.g. 有りそう |
| Reading | `reading(EntryFurigana)` → ありそう |
| Furigana | `EntryFurigana` (already Anki notation) |
| Meaning | `Meaning` (as-is, accept clutter) |
| Term Audio | `Word Audio` |
| Sentence | *(empty — KS has no sentences)* |
| **dropped** | ID, Entry, Translation, Screenshot, Sentence Audio, Images, Example Sentences, Frequency |

Any Kinomoto field not listed (SentenceFurigana, Cloze*, Context, etc.) stays blank —
filled by the later pitch/cloze/audio features.

### Derivation edge cases (handled, not errors)

- Plain-kanji `Target Word` (e.g. `一`, no reading) → `Reading` blank, `Furigana`=`一`
  (a gap for the later pitch feature).
- Kana-only target → Term = Reading = Furigana = the kana.
- Kanji Study reading is derived from the clean `EntryFurigana` (Anki notation), so the
  messy `Entry` markup never needs parsing.

## Front-template fix (bundled into this milestone)

Converted MyNote/KS notes have **no cloze fields**, so Kinomoto's front falls to
`{{Sentence}}` = raw jrp markup. Fix: when there's no `Cloze Inside`, the front renders
the sentence as **plain text (markup stripped)** instead of raw `{..}`. No data change;
target word still shown as the `Term` above. Highlighting arrives later with the
cloze/pitch feature. (Lives with the goal-#4 template work, applied by `template.py`.)

## Components

In-process add-on, triggered from **Tools → "AnkiAlign: Unify note types…"**.

- `markup.py` — pure, no Anki imports: `jrp_surface()`, `jrp_reading()`,
  `jrp_furigana()`, `strip_markup()`, `entryfurigana_split()`. (Hardened from the
  preview harness `build.py`.)
- `mapping.py` — per source: the `dict → dict` mapping fn + dropped-field list.
- `convert.py` — engine: select in-scope notes, build plan, backup, `change()` +
  transforms in one undoable op.
- `preview.py` — read-only dry-run dialog.
- `template.py` — one-time write of the Kinomoto front fix + goal-#4 styling
  (`custom_css` from `research/template-preview/build.py`).
- `tests/` — pytest over `markup`/`mapping`; integration run on a copy of
  `collection.anki2`.

## Data flow

1. **Scan**: per source, collect note-ids whose card is in an in-scope deck; build plan
   (counts, dropped fields, N sample before→after).
2. **Preview dialog** (read-only): shows the plan and runs every mapping fn over all
   selected notes **with no mutation**, surfacing any transform error *before* changes.
3. **Convert**: `col.create_backup(force=True, wait_for_completion=True)`; report path.
4. **One undoable `CollectionOp`**: per source `models.change(...)` (cmap `{0:0}`), then
   post-transform repopulates Kinomoto fields from the snapshot. Save.
5. Apply the template front fix once.
6. **Report**: N converted, fields dropped, backup path. Reversible via Ctrl+Z + backup.

## Safety / error handling

- Forced full `.colpkg` backup before any write; path surfaced in the report.
- Whole run merges into **one undo entry** (Ctrl+Z reverts everything).
- Preview is read-only and does the real mapping work dry → errors surface first.
- Abort guards: missing target note type, or zero in-scope notes → stop, no backup.
- Idempotent: selection by *source* note type → re-running never re-touches Kinomoto.
- Media untouched: field values keep `[sound:…]`/`<img>` filenames; same
  `collection.media`.

## Testing

- **Unit (`markup.py`)**: `surface/reading/furigana` vs real extracted examples. Free
  ground truth: Yuuna and Kinomoto already store `Reading`+`Furigana`, so re-derive from
  markup and compare; plus the 30k+ `{unit}` examples.
- **Unit (`mapping.py`)**: each source's `dict→dict` over representative + edge notes.
- **Integration**: full engine on a **copy** of `collection.anki2` (headless
  `anki.Collection`); assert per-type counts after, Kinomoto fields populated as mapped,
  **scheduling preserved** (`ivl`/`due`/`reps`/`type` unchanged), no media refs lost.
- **Final eyeball**: a few converted notes through the existing preview harness.

## Out of scope (explicit)

Pitch generation, word audio (offline local-audio DB), cloze generation, frequency
backfill, deleting emptied Yuuna/KS note types. All separate later features that fill
the now-unified schema.
