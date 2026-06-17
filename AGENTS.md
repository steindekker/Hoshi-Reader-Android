# Anki Enrich

See `PRD.md` for what this tool does. Code is the source of truth; the PRD stays
tiny and intentionally omits implementation detail.

## Stack

- Python ≥ 3.11, managed with `uv` (`uv run anki-enrich`).
- `anki` library — reads/writes the local collection (`.anki2`) and syncs with
  AnkiWeb (login via env credentials, no desktop app needed).
- `sudachipy` + `sudachidict-full` — Japanese tokenization for JRP sentence
  markup.
- `pytest` for tests.

## Conventions

- Dry-run by default; `--apply` to write (back up the collection first, then sync).
- Single user, single collection — enrichment passes only touch cards missing the
  relevant field.

## The poc

A proof-of-concept lives in `poc/`. Use it as inspiration only — **nothing may be
linked against or compiled against it**. If you need data (dicts, etc.), copy it
over.

## Running

- `ANKIWEB_USERNAME`/`ANKIWEB_PASSWORD` must be set; the tool keeps its own
  collection at `var/collection.anki2` (never the desktop profile).
- `uv run anki-enrich` — pull latest, dry-run preview. `--apply` writes + pushes.
- `uv run anki-enrich dedupe --apply` — destructive duplicate merge (separate).
- First run builds `var/defs.sqlite` from `dicts/` (the J-J chain + JMdict);
  the accent index ships in `ankienrich/data/`.
- Example sentences build `var/examples.sqlite` from J→E dicts in `dicts/`
  (jitendex/saito/yourei) + the Tatoeba dump in `dicts/tatoeba/`; absent those the
  step is skipped. Fills the `Examples` field (an expandable 例文 block, ≤3, deduped).
- Term-audio enrichment builds `var/audio.sqlite` from the `local-audio-yomichan`
  add-on (its `entries.db` + `*_files/`) on first run — like `defs.sqlite`/
  `examples.sqlite`. The runtime reads only `var/audio.sqlite` (clip bytes embedded),
  so the add-on is a build-time-only input; without it installed the build is
  skipped, not failed.
