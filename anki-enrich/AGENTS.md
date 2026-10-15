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
- Loudness leveling runs right after, over *every* clip on a card (most Term
  Audio is mined on the phone and never sees the store). ffmpeg measures EBU
  R128, then applies one linear gain toward -16 LUFS, clamped under -1 dBTP —
  no limiting, so the pitch contour survives. The levelled clip gets a `norm_`
  name and the note is repointed; that prefix is the "already done" marker, so
  the pass is idempotent and stays correct across a swapped audio store. Needs
  ffmpeg on PATH (installed in the image); absent it the step is skipped.

## The Hoshi enrichment pack

`uv run anki-enrich pack` builds `var/pack/hoshi-pack.sqlite` + `pack.json` for
the Hoshi Reader app (format + lookup contract documented in
`ankienrich/pack.py`). Every value comes from the same functions the passes call
(`definition_for`, `examples_for`, `accents_for` + `pitch_svg`, the freq lookup),
and the nightly fields pass (`ankienrich/fill.py`) fills the collection from the
pack itself through `pack.find`, the app's lookup — one route, sources → pack →
app and collection. Don't add word-level note writing outside `fill.py`. Adding a
key to `fields_for` reaches the app as `{ae-<key>}` with no app change (map it to
a field in `fill.py` for the collection). A run rebuilds the pack when a source
index is newer (`pack.ensure`); `--overwrite-fields` re-asserts definitions and
examples over every card. `deploy/push.sh` rebuilds the pack on the box;
`tailscale serve` publishes it.

The pack build also publishes the **sentence model** (`ankienrich/sentence_model.py`):
the accent index as an indexed SQLite (`jrp-accent.sqlite`, every `AccentIndex`
query precomputed by `AccentIndex` itself) and sudachidict_full's `system.dic`
(`sudachi-system.dic`), listed under `"sentence"` in `pack.json`, each versioned on
its own content. The app uses them to write JRP markup into `{ae-sentence}` at
mining time (its Kotlin port of `jrp_fill`), so new cards arrive with sentence
markup; the nightly sentence pass stays for older cards and whole-collection
reruns. A change to `jrp_fill` must be mirrored in the app's port (and its parity
goldens).

## The Telegram bot

`uv run anki-enrich-bot` (needs `TELEGRAM_BOT_TOKEN`/`TELEGRAM_CHAT_ID`). A thin,
single-user run trigger: tap ▶️ Run (or `/run`) to pick which fields to overwrite,
then Apply shells out to `anki-enrich --apply [--overwrite-*…] --notify` — the bot
never blocks on a run and never re-implements the pipeline; the spawned CLI sends
its own report. An empty selection fills missing fields only. See `ankienrich/bot.py`.
