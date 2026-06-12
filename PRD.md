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

### Enrichment

1. **Term audio** — fill where missing; replace jpod audio with a higher-quality
   source. Priority: NHK → Shinmeikai → Forvo → Jpod.
2. **Pitch graphs** — generate where missing.
3. **Sentence** — enrich with JRP markup.
4. **Definitions** — fill where missing: a J-J definition on top (first hit in
   the chain 三省堂 → 広辞苑 → 実用 → 大辞泉) plus JMdict (J-E) glosses below it,
   each shown whenever available.
5. **Cloze** — set the cloze fields correctly.

### Dedupe

Merge duplicate cards, preserving review history.

### Template

Set the card template to the one in the repo.

## Config

- AnkiWeb username / password (env)
- Target note type name
- Field mapping: Term · Term Furigana · Sentence · Sentence Furigana ·
  Term Audio · Definition · Pitch · Cloze Start · Cloze Inside · Cloze End
