# Handoff: Term-audio backfill (AnkiAlign goal #3)

Date: 2026-06-04. For the main AnkiAlign agent. Brainstorm is settled; this is the
decided design + the immediate next step. Companion artifacts live in
`research/audio-compare/` (listening site + source-quality research).

## Goal

Backfill the **`Term Audio`** field on `jidoujisho Kinomoto` cards that currently
have none, using offline native-speaker word audio. (Goal #3, per
`research/HANDOFF-mobile-review-and-ecosystem.md:46`.) Sentence Audio is **out of
scope** for this pass.

## Data source (installed, decided)

The **`local-audio-yomichan`** add-on's offline DB — NOT live Forvo scraping.

- AnkiWeb code `1045800357`. Path: `~/.local/share/Anki2/addons21/1045800357/`.
- Opus collection (2023-06-11) extracted into `user_files/`; Forvo was bundled.
  ~382k files: jpod 134103, nhk16 102823, forvo 78835, shinmeikai8 66727.
- Index = `user_files/entries.db` (SQLite), schema (`db_utils.py`):
  `entries(id, expression, reading, source, speaker, display, file)`.
  - `source ∈ {jpod, jpod_alternate, nhk16, shinmeikai8, forvo}`
  - `reading` is NULL for forvo (term-only) and some kana/counter rows.
  - `speaker` holds the Forvo username (NULL otherwise).
  - `file` is relative to each source's media dir (resolve per source — see below).
  - `fill_jmdict_forms()` backfills kanji/kana variant `expression`s, so lookups
    resolve across surface forms for free. **Prefer querying `entries.db` over
    re-parsing the raw indexes** so you inherit this.
- **entries.db is built lazily by the add-on on Anki launch** (`main_window_did_init`
  → `attempt_init_db()`; rebuilds when the table is empty or `entries_version.txt`
  bumps). It was 0 rows right after extraction. **Relaunch Anki once to populate it
  (~382k rows + JMdict backfill, one-time minute or two) before running the spike.**

## Decided behaviour

### Source priority (stop at first hit)
`nhk16 → shinmeikai8 → forvo → jpod`

Rationale (full report: `research/audio-compare/RESEARCH.md`): nhk16 is the
broadcast-normative pitch-accent gold standard; shinmeikai8 is the second curated
accent dictionary; both are `(term, reading)`-keyed so homographs get the right
accent. This is also the upstream `local-audio-yomichan` default ("Japanese
correctness over literal audio quality", v1.6.0). jpod is **last** despite clean
studio audio because it is "confidently wrong on pitch" (TheMoeWay) — the worst
failure for a pitch-review workflow.

### Forvo: pin the speaker
The bundled Forvo set is only **5 curated voices** (strawberrybrown, skent,
poyotan, kaoring, akitomo), dominated by `strawberrybrown` (covers ~16/20 in the
spot-check). User listened and chose her voice. So when the chosen source is forvo:
**prefer `speaker = 'strawberrybrown'`; fall back to the others** (any order is
fine; skent/poyotan have the next-best coverage). This makes "forvo" effectively
"one trusted voice," which is the basis on which it was ranked above jpod.

### Match policy
- For nhk16 / shinmeikai8 / jpod: require exact `expression = Term AND reading =
  Reading`. (Reading-keyed → homograph-safe.)
- For forvo: **term-only** (`expression = Term`, reading is NULL). Because it can't
  disambiguate readings, **never fall through to forvo when a reading-keyed
  dictionary already hit.** With stop-at-first-hit + the order above, forvo only
  fires when nhk16 AND shinmeikai8 both miss — i.e. the long tail (names/slang/rare).
- No exact hit anywhere → leave `Term Audio` empty and report it. No TTS.

### Resolving the card's (Term, Reading)
Kinomoto has `Term` and `Reading` fields. `Reading` can be empty (bare-kanji cases
where `markup.jrp_reading` returned ""). If `Reading` is empty, the reading-keyed
lookups can't run cleanly — either skip to forvo term-only, or skip the card and
report. Decide in the spike from how many cards this affects.

### Apply step
For the chosen row: resolve the real file path, copy into `collection.media` (dedupe
on a stable name, e.g. `<source>_<basename>` or a content hash to avoid collisions),
write `[sound:<filename>]` into `Term Audio`. Reuse `markup.as_sound` shape.

File-path resolution (entries.db `file` is per-source-media-relative; cf.
`research/audio-compare/build.py` which already resolves all four correctly):
- jpod / shinmeikai8: `user_files/<src>_files/media/<file>`
- nhk16: `user_files/nhk16_files/<file>` (file already includes `audio/…`)
- forvo: `user_files/forvo_files/<file>` (`<speaker>/<term>.opus`)
Or just reuse the add-on's own `get_media_dir_path()` helpers.

### Workflow
Standalone **preview→apply Tools-menu action**, reusing the existing read-only
dry-run pattern (`ankialign/preview.py` + `convert.py:build_plan`). Re-runnable as
the audio DB grows. **Not** folded into the unify pass (which only fires at convert
time and is awkward to re-run). The dry-run dialog should show, per card: chosen
source, speaker (if forvo), and the resolved reading — plus aggregate coverage
(how many filled by each source, how many left empty).

### Scope
In-scope Kinomoto cards only (reuse `config.in_scope` deck exclusions —
Core2.3k / 文法 stay out), `Term Audio` empty.

## Immediate next step — coverage spike first (BRIEF → REPORT)

Mirror the pitch-pipeline rhythm: measure before building. After relaunching Anki to
build entries.db, run a read-only spike over the real `collection.anki2`:

1. Count in-scope Kinomoto cards with empty `Term Audio` (the target N).
2. For each, run the lookup chain against entries.db. Report: % exact dict hit
   (split nhk16 / shinmeikai8), % jpod-only, % forvo-tail (and how many of those
   have a strawberrybrown clip vs. only other speakers), % nothing.
3. Bucket the residual `nothing` set (rare/names? missing-reading cards?).
4. Spot-check ~25 resolved picks by ear for accent sanity.

Deliverable: `research/audio-compare/COVERAGE.md` with the numbers + a go/no-go on
whether the gap is worth the pass and whether the forvo tail pulls its weight. THEN
write the implementation plan (writing-plans).

## Mobile / AnkiDroid (from HANDOFF-mobile-review)
Audio must be a `[sound:…]` field (native "Automatically play audio"), never
JS-driven. Opus files are small — keep per-card media lean. No template changes
needed for this goal; we only fill an existing field.

## Open questions for the human
- Spike-first (recommended) vs. straight to implementation plan? (User leaning: TBD —
  default to spike-first.)
- Missing-`Reading` cards: forvo-term-only fallback, or skip+report? (decide from spike)
- Multiple candidate clips for the winning source: take the first / dictionary-canonical
  one (assumed), or surface a choice? (assumed: single best pick, no UI)
- Re-run policy: only-empty cards (assumed), or also offer to upgrade existing
  lower-priority audio to a higher-priority source later?

## Reference artifacts
- `research/audio-compare/RESEARCH.md` — source-quality verdict + citations.
- `research/audio-compare/build.py` + `site/` — 20-word listening comparison;
  working reference for parsing each source's index and resolving file paths.
- `research/HANDOFF-mobile-review-and-ecosystem.md:46` — original goal-#3 note.
