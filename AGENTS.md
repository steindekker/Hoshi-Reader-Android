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

## The Telegram bot

`uv run anki-enrich-bot` (needs `TELEGRAM_BOT_TOKEN`/`TELEGRAM_CHAT_ID`). Tap ▶️ Run
to fire the enrichment suite, or **just send a word** to mine a new card: the bot
previews the formatted definition (dict HTML → Telegram HTML via `mine.tg_html`)
with pitch + JPDB frequency folded into the header, plays the term audio, lets you
pick a Massif example sentence (local `examples.sqlite` fallback) and a Bing image
(or skip either), then writes a `jidoujisho Kinomoto` card tagged `ankienrich::mined`
and syncs — one add at a time under the same run-lock as the nightly job. The
reading picker for multi-reading words is ordered by frequency. 0-hit words are refused.

Word frequency comes from a JPDB Yomitan frequency dict extracted to `dicts/jpdb/`;
first run builds `var/freq.sqlite` (skipped if absent — the preview/picker just omit
the rank, like the other build-time inputs). The mined card's `Frequency` field is
filled from it.

Both searches are **prefetched at the preview step** (`bot.py` `_enter_preview` →
`mine.prefetch_sentences` + `mine.prefetch_images`): the Massif fetch
(`massif.prefetch`, a lightweight 1-slot executor) and the Bing search
(`images.prefetch`, a warm Chromium on its own worker thread) run in the
background while the user reads the card, so each pick is usually instant.

The image step uses headless Chromium via `ankienrich/images.py` (the `images`
dep group). The bot `Dockerfile` installs the browser
(`playwright install --with-deps chromium`, ~400 MB, cached after the deps layer);
without a browser the step degrades to "no images → skip" (see
`docs/image-search-handoff.md`). Follow-up: a preview-time duplicate warning (today
a duplicate is only caught at Apply, after pull).
