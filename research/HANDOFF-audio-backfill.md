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

## Coverage spike — DONE (2026-06-04), verdict: GO

`research/audio-compare/coverage_spike.py` (read-only, immutable-mode; safe with
Anki open) → `research/audio-compare/COVERAGE.md`. Headline numbers:

- 8,240 in-scope Kinomoto notes; **only 583 (7.1%) lack `Term Audio`** = target N.
- Decided chain fills **490 / 583 = 84.0%**: nhk16 280 (48%), shinmeikai8 98 (17%),
  forvo 99 (17%), jpod 13 (2%); **65% land on a reading-correct accent dictionary**.
- **93 misses (16%) are genuinely uncoverable**, not a tuning gap: 70 = multi-word
  phrases / compound slang / numbers absent from any single-word source (見つめ直す,
  リア充, １対１); 16 = empty reading; 7 = term-exists-but-reading-mismatch (the only
  marginally recoverable bucket — diminishing returns).

Decisions the data settled:
- **forvo > jpod is safe to keep:** only 9 cards where forvo beats an available jpod
  reading-hit, almost all multi-word non-homographs → negligible risk.
- **strawberrybrown is a minority of the forvo tail:** only 27/99 forvo wins have her
  clip; 72 fall back to skent/poyotan/etc. (the tail is multi-word slang she didn't
  record). Keep the fallback — strawberrybrown-only would cut coverage 84%→~72%.
- **empty-Reading cards are minor (30 of 583).** Don't over-engineer; forvo term-only
  catches some, the rest fall into the reported residual. No special handling needed.

**Next: write the implementation plan (writing-plans skill).** Approach is validated;
no further research needed. Optionally spot-check the 9 forvo-over-jpod cards by ear
once built, but not a blocker.

## Sibling task: re-pitch existing audio (SEPARATE action — keep out of goal #3)

User decided to keep this distinct from the empty-fill backfill above. Same data
source + media-copy mechanics, different population and intent.

**Why:** existing Term Audio is almost all jidoujisho-mined JapanesePod101, which has
frequent wrong pitch accent (the reason jpod is last in the fill chain). User wants it
replaced with accent-dictionary audio.

**Can't identify jpod forensically** — filenames are opaque jidoujisho timestamps;
clips (~0.9s) are too short for Chromaprint; durations inconclusive. Irrelevant: a
dictionary hit is authoritative regardless of the current file's origin.

**Decided policy — dictionary-only replacement:**
- Replace existing Term Audio **only** where `nhk16` OR `shinmeikai8` has an exact
  (term, reading) hit (prefer nhk16). **Never** replace with forvo or jpod.
- Leave forvo-only and no-source cards untouched.
- **Non-destructive:** keep the old mp3 in `collection.media`; rewrite only the
  `[sound:]` field reference. Plus backup-before-op. Fully reversible.
- Worst case per card is lateral (current audio already good → swapped for NHK);
  never a downgrade.

**Population (`research/audio-compare/upgrade_spike.py` → COVERAGE.md):** of 7,655
in-scope Kinomoto cards WITH Term Audio, **7,185 (93.9%) are replaceable** with a
reading-correct dictionary recording (nhk16 6164 / shinmeikai8 6855 union); 290
forvo-only + 180 no-source left as-is; 68 empty-reading.

Build as its own Tools action (its own preview→apply), after the fill pass exists —
it can reuse the same lookup + media-copy helpers.

## Mobile / AnkiDroid (from HANDOFF-mobile-review)
Audio must be a `[sound:…]` field (native "Automatically play audio"), never
JS-driven. Opus files are small — keep per-card media lean. No template changes
needed for this goal; we only fill an existing field.

## Open questions for the human (remaining after spike)
- Multiple candidate clips for the winning source: take the first / dictionary-canonical
  one (assumed), or surface a choice? (assumed: single best pick, no UI)
- Re-run policy: only-empty cards (assumed), or also offer to upgrade existing
  lower-priority audio to a higher-priority source later?
- strawberrybrown-only for forvo (27 fills, her voice only) vs. fallback to other curated
  speakers (99 fills)? (recommended + assumed: keep fallback.)

## Reference artifacts
- `research/audio-compare/RESEARCH.md` — source-quality verdict + citations.
- `research/audio-compare/build.py` + `site/` — 20-word listening comparison;
  working reference for parsing each source's index and resolving file paths.
- `research/HANDOFF-mobile-review-and-ecosystem.md:46` — original goal-#3 note.
