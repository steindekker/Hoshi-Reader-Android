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
- Term audio comes from a prebuilt store at `var/audio.sqlite` — the android.db
  packaged export (local-audio-yomichan / AnkiConnect-Android format: `entries` +
  `android` tables, blobs embedded). It is placed there directly (no build step);
  the runtime opens it read-only. Lookup is a port of Hoshi Reader's resolver:
  reading-match first, then source priority
  (nhk16→daijisen→shinmeikai8→jpod→…→forvo_ext2). Absent the store the step is
  skipped, not failed.

## The Telegram bot

`uv run anki-enrich-bot` (needs `TELEGRAM_BOT_TOKEN`/`TELEGRAM_CHAT_ID`). A thin,
single-user run trigger: tap ▶️ Run (or `/run`) to pick which fields to overwrite,
then Apply shells out to `anki-enrich --apply [--overwrite-*…] --notify` — the bot
never blocks on a run and never re-implements the pipeline; the spawned CLI sends
its own report. An empty selection fills missing fields only. See `ankienrich/bot.py`.
