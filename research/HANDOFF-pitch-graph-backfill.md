# Handoff: Term pitch-graph backfill (feature branch, ready, NOT merged)

Date: 2026-06-04. Written for the AnkiAlign agent working off **main**. The feature is **complete, tested, and green** but deliberately left on its own branch for the user to review/run in Anki before merging. This is **goal #2 (term-level pitch)** — the standalone word's `Pitch Accent` SVG graph only. Sentence inline pitch is explicitly **out of scope** (user is using a separate third-party JRP add-on for that).

## Where it lives

- **Branch:** `feat/pitch-graph-backfill` (7 commits + 1 docs commit, stacked cleanly on `d6d4c78`, which is current `main` HEAD — fast-forward mergeable, main hasn't moved).
- **Worktree:** `~/projects/anki-align.wt/pitch-graph-backfill` (created with `git worktree add`; do NOT reuse without the user's OK per their global rule).
- **Spec:** `docs/superpowers/specs/2026-06-04-pitch-graph-backfill-design.md`
- **Plan:** `docs/superpowers/plans/2026-06-04-pitch-graph-backfill.md`
- **Dict study behind the dict choice:** `research/pitch-pipeline/REPORT-pitch-dicts.md` (verdict: use **アクセント辞典v2**, primary accent only — 97.5% coverage of the user's mined vocab, accuracy on par with NHK).

These spec/plan/report files exist **only on the branch**, not on main. To read them from main: `git show feat/pitch-graph-backfill:docs/superpowers/specs/2026-06-04-pitch-graph-backfill-design.md` (etc.), or just `cd` into the worktree.

## What it does

Adds a Tools-menu action **"AnkiAlign: Backfill pitch graphs…"** (dry-run dialog → forced backup → fill). For every `jidoujisho Kinomoto` note whose `Pitch Accent` field is **empty** and which has a `Reading`, it looks up `(Term, Reading)` in a shipped アクセント辞典v2 index and writes a self-contained, jidoujisho-style **SVG** pitch graph (primary accent). Idempotent; never overwrites an existing graph; leaves dict-misses empty (no fabrication). Same safety pattern as the existing unify flow (`convert.backup_collection` first).

## New files (all on the branch)

| File | Role | Imports |
|---|---|---|
| `ankialign/pitch_svg.py` | Pure SVG generator: `split_moras`, `render_svg(reading, accent)` | stdlib only |
| `ankialign/pitch_index.py` | Pure lookup: `PitchIndex.load/.lookup`, `to_hira` | stdlib only |
| `ankialign/data/pitch_index.marshal.gz` | Shipped index, **3.0 MB / 307,304 keys**, `"term\treading_hira" → primary position` | — |
| `ankialign/pitch.py` | Engine: `build_pitch_plan(col)`, `backfill_pitch(col)` | anki `col` only |
| `research/pitch-pipeline/build_index.py` | Offline index builder (run once) | — |
| `ankialign/preview.py` (modified) | `show_pitch()` dry-run dialog | aqt |
| `ankialign/__init__.py` (modified) | registers the second menu action | aqt |
| `tests/test_pitch_svg.py`, `test_pitch_index.py`, `test_pitch_engine.py`, `test_pitch_groundtruth.py` | tests | — |

Pure/impure boundary matches the rest of the codebase (markup-style pure modules; anki-only engine; aqt-only GUI).

## Verification status (independently re-run, not just agent-reported)

- `uv run pytest -q` → **39 passed**.
- **Ground-truth audit** (`test_pitch_groundtruth.py`): parses the accent out of the 2,164 existing jidoujisho SVGs and compares to the dict. **dict-found 2163/2164 (100%), agreement ~100%** — a single genuine dict disagreement (`あまつさえ`, SVG primary 3 vs dict 1). Validates the dict; the SVG generator is pinned separately by exact-string unit tests.
- **Dry-run against the real collection** (`build_pitch_plan`): **filled 157, not_found 207, skipped 61** (sums to the 425 empty Pitch Accent fields). The 207 not_found are overwhelmingly idioms / 4-char compounds / multi-word phrases (容姿端麗, 一刀両断, 猫を被る) — exactly the words jidoujisho itself couldn't fill; not single headwords in アクセント辞典v2. Correct per spec (leave empty).

## To pick this up from main

**To merge (fast-forward, no conflicts expected):**
```bash
cd ~/projects/anki-align
git merge --ff-only feat/pitch-graph-backfill
git worktree remove ~/projects/anki-align.wt/pitch-graph-backfill   # after merge
```
**To review first in Anki (recommended before merge — it mutates the real collection):** load the add-on from the worktree, run Tools → "AnkiAlign: Backfill pitch graphs…", check the dry-run counts and a few filled cards, confirm the backup landed.

**To re-run the audit yourself** (collection is gitignored, not in the worktree):
```bash
cd ~/projects/anki-align.wt/pitch-graph-backfill
ln -sf ~/projects/anki-align/collection.anki2 ./collection.anki2   # gitignored
uv run pytest tests/test_pitch_groundtruth.py -v -s
```
**To rebuild the index** (only if アクセント辞典v2 changes): `uv run python research/pitch-pipeline/build_index.py` (reads the extracted dict by absolute path under `research/pitch-pipeline/dicts/d/アクセント辞典v2/`, gitignored).

## Caveats / things the next agent should know

1. **One justified deviation from the plan:** ~22% of existing cards stack *multiple* SVG graphs (`<br>`-separated). The plan's `_accent_from_svg` scanned the whole field and broke; fixed to parse only the first `</svg>` block (jidoujisho lists primary first) and to register the `unicase` SQLite collation the schema columns need. Thresholds were not weakened.
2. **The 425-note gap reconciles** with the earlier collection analysis (2681 Kinomoto − 2256 already-pitched = 425). After the unify conversion runs (Kanji Study → Kinomoto adds pitch-less notes), re-running the backfill covers them too — it's idempotent.
3. **No unify tail-call was added** — backfill is a separate manual action by design. If you want a fresh conversion to come out pre-pitched in one pass, wire `pitch.backfill_pitch` into `preview.do_convert` after `normalize_media` (a few lines); intentionally deferred.
4. **Dict choice is recorded in memory** (`pitch-dict-choice`): アクセント辞典v2 primary. Don't relitigate without re-reading the REPORT.
5. **Out of scope, do not build here:** sentence inline `{unit;accent}` pitch (third-party add-on), and coverage of the 207 idiom misses (would need a tokenizer / phrase-aware source).
