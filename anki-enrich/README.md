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
- **Sentence** — add JRP markup (Sudachi tokenization + アクセント辞典v2 accents).
- **Fields** — fill the word-level fields from the Hoshi pack (below), the same
  values the app writes at mining time:
  - a J-J definition (三省堂 → 広辞苑 → 実用 → 大辞泉) plus JMdict J-E glosses below it;
  - an expandable 例文 block (≤3, deduped) from Jitendex/Saito/Yourei + the Tatoeba dump;
  - the pitch-accent graph and the JPDB frequency rank.
- **Cloze** — set the cloze fields.

A separate `dedupe` pass merges duplicate cards while preserving review history.

## Hoshi enrichment pack

The main job going forward: `anki-enrich pack` precomputes every field above that
depends only on (term, reading) — definition + source, pitch graph, 例文 block,
JPDB rank, and the tags that tell these passes a card is done — for all ~845k
dictionary headwords, into one SQLite file (`var/pack/hoshi-pack.sqlite`, ~380 MB,
plus a `pack.json` manifest). The Hoshi Reader fork downloads it over Tailscale
and fills `{ae-*}` handlebars offline at mining time, so phone-mined cards are
already final. Format and lookup contract: `ankienrich/pack.py`.

The nightly fields pass reads the same pack (`ankienrich/fill.py`), so there is one
route: sources → pack → app and collection. A run rebuilds the pack first when a
source index is newer. To push a changed source over every existing card, rebuild
its index and run `anki-enrich --apply --overwrite-fields`. The sentence passes
(JRP markup, cloze resync) and audio levelling still run here nightly.

The same build publishes a separately versioned **sentence model** next to the
pack — the accent index as SQLite (`jrp-accent.sqlite`, ~33 MB) and the Sudachi
full dictionary (`sudachi-system.dic`, ~360 MB) — listed under `"sentence"` in
`pack.json`. With it the app fills `{ae-sentence}` with the JRP-marked-up mining
sentence offline, so new cards arrive with sentence markup; the nightly sentence
pass remains for older cards and `--overwrite-sentences` reruns. Format:
`ankienrich/sentence_model.py`.

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
uv run anki-enrich --apply --overwrite-fields   # re-assert defs + examples on every card
uv run anki-enrich dedupe --apply     # destructive duplicate merge
uv run anki-enrich pack               # build the Hoshi enrichment pack (offline)
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

## Add a note (`anki-enrich add`)

Create a **Hoshi** note (formerly jidoujisho Kinomoto), then fill-missing enrich **that note only**.
Shares the working collection with the normal enrich VAR
(`ANKI_ENRICH_VAR`, default `<repo>/var`; on box use
`/opt/anki-enrich/data`).

Dry-run (default) — preview fields, dedup, lock; no write/push:

```bash
export ANKI_ENRICH_VAR=/opt/anki-enrich/data   # share live data on box
uv run anki-enrich add 食べる --reading たべる --sentence "寿司を食べる。"
uv run anki-enrich add 朝顔 --reading あさがお --image /abs/path/to/shot.png
```

Apply — pull → add (dedup) → enrich that note (fill-missing) → single AnkiWeb push:

```bash
uv run anki-enrich add 食べる --reading たべる --sentence "寿司を食べる。" --apply
```

Flags: `TERM`, `--reading`, `--sentence`, `--furigana`, `--image PATH` (absolute),
`--deck` (default Default), `--apply`. Exit 2 on duplicate Term(/Reading); exit 3 if
`run.lock` is held.
