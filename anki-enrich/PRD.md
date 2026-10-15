# Anki Enrich

A personal CLI that keeps my Anki collection consistent. It syncs the latest
collection from AnkiWeb, applies the passes below, and pushes back — without
opening the Anki desktop app.

Single user, single collection. A one-time bulk cleanup is already done, so
passes only touch newly-added cards (those still missing the relevant field).

## Behavior

Default run is a **dry-run**: it prints what each pass would change and how many
cards it would touch. `--apply` backs up the collection, writes in place, then
pushes to AnkiWeb.

### Status reports

`--notify` sends the run's result to a personal Telegram chat — per-pass counts, the
newly-defined terms, the sync outcome, or a failure naming the pass that broke. Meant
for an unattended nightly run so the outcome is visible from a phone. `anki-enrich-bot`
is a companion listener that triggers a run on demand via `/run` (responding only to
the configured chat); a lock keeps a manual run from colliding with the nightly one.

### Enrichment

1. **Term audio** — fill where missing; replace jpod audio with a higher-quality
   source. Priority: NHK → Shinmeikai → Forvo → Jpod.
2. **Sentence** — enrich with JRP markup.
3. **Fields** — fill from the Hoshi pack where missing: a J-J definition on top
   (first hit in the chain 三省堂 → 広辞苑 → 実用 → 大辞泉) plus JMdict (J-E)
   glosses below it, examples, pitch graph and frequency. Optionally re-assert
   them over the whole collection (e.g. after switching a source).
4. **Cloze** — set the cloze fields correctly.

### Hoshi enrichment pack

Precompute every field that depends only on the word (definition, pitch graph,
examples, frequency, done-tags) into one file the Hoshi app downloads over
Tailscale, so cards mined on the phone are final offline. This is the tool's
primary purpose going forward. The fields pass above reads the same pack, so the
app and the collection never disagree. Beside it, publish the sentence model
(accent index + Sudachi dictionary) so the app writes the Sentence field's JRP
markup itself at mining time.

### Dedupe

Merge duplicate cards, preserving review history.

### Template

Set the card template to the one in the repo.

## Config

- AnkiWeb username / password (env)
- Telegram bot token / chat id (env) — for `--notify` and `anki-enrich-bot`
- Target note type name
- Field mapping: Term · Term Furigana · Sentence · Sentence Furigana ·
  Term Audio · Definition · Pitch · Cloze Start · Cloze Inside · Cloze End
