# Anki Enrich

<p align="center">
  <img src="assets/bot-avatar.png" alt="anki-enrich-bot avatar — 言霊 (kotodama)" width="320">
</p>

A personal CLI that keeps a single Anki collection consistent. It pulls the
latest collection from AnkiWeb, runs a set of enrichment passes, and pushes back
without opening the Anki desktop app.

Each pass only touches cards still missing the relevant field, so re-running is
safe. See [`PRD.md`](PRD.md) for the full behavior spec.

## Enrichment passes

- **Term audio** — fill where missing; prefer NHK → Shinmeikai → Forvo → Jpod
  (the prebuilt android.db term-audio store at `var/audio.sqlite`).
- **Pitch graphs** — generate accent graphs where missing.
- **Sentence** — add JRP markup (Sudachi tokenization + アクセント辞典v2 accents).
- **Definitions** — a J-J definition (三省堂 → 広辞苑 → 実用 → 大辞泉) plus JMdict
  J-E glosses below it.
- **Examples** — an expandable 例文 block (≤3, deduped) from
  Jitendex/Saito/Yourei + the Tatoeba dump.
- **Cloze** — set the cloze fields.

A separate `dedupe` pass merges duplicate cards while preserving review history.

## Setup

Requires Python ≥ 3.11 and [`uv`](https://docs.astral.sh/uv/).

```sh
# required — AnkiWeb sync
export ANKIWEB_USERNAME=...
export ANKIWEB_PASSWORD=...

# optional — Telegram run reports (`--notify`) and the `anki-enrich-bot` listener
export TELEGRAM_BOT_TOKEN=...
export TELEGRAM_CHAT_ID=...      # your own chat id; the bot ignores everyone else
```

| Variable | Required | Purpose |
| --- | --- | --- |
| `ANKIWEB_USERNAME` / `ANKIWEB_PASSWORD` | yes | AnkiWeb login for pull/push |
| `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` | for `--notify` / the bot | where run reports go and which chat may drive the bot |

The tool keeps its own collection at `var/collection.anki2` (never the desktop
profile). On first run it builds `var/defs.sqlite` from `dicts/` and, if the J→E
dicts and Tatoeba dump are present, `var/examples.sqlite`. The accent index ships
in `ankienrich/data/`.

Optional sources, skipped (not failed) when absent:

- `var/audio.sqlite` (android.db term-audio store) → term audio
- J→E dicts in `dicts/` + Tatoeba dump → example sentences

## Usage

```sh
uv run anki-enrich              # pull latest, dry-run preview (no writes)
uv run anki-enrich --apply      # back up, write in place, push to AnkiWeb
uv run anki-enrich --apply --notify   # also send the result to Telegram
uv run anki-enrich dedupe --apply     # destructive duplicate merge
uv run anki-enrich-bot          # Telegram listener (see below)
```

Every run is a **dry-run by default**: it prints what each pass would change and
how many cards it would touch. `--apply` backs up the collection first, then
writes and syncs.

## Telegram bot

`anki-enrich-bot` is a single-user listener (it answers only `TELEGRAM_CHAT_ID`)
and a thin run trigger: tap ▶️ Run (or `/run`) to pick which fields to overwrite,
then Apply. It shells out to `anki-enrich --apply --notify` — an empty selection
fills missing fields only — and the spawned run sends its own report.

## Development

```sh
uv run pytest
```

A proof-of-concept lives in `poc/` as a read-only reference — nothing is linked
or compiled against it.
