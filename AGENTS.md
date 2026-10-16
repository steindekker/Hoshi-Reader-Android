# Hoshi Reader (Stein's fork)

A personal fork of Hoshi Reader Android (Kotlin, Jetpack Compose). It follows its own product direction; upstream is something to merge from, not a spec to match.

## What matters, in order

1. **Dictionary**: fast, rich lookups; the main reason the app exists.
2. **Anki card creation**: a good card with no setup, the "omakase" path. One tap opens "Add to Anki", a long press adds instantly, and the card lands in the shipped Hoshi note type with definitions, pitch, frequency, example sentences, audio and a pitch-marked sentence filled in.
3. **Reading**: books, and Live (visual novels on the Steam Deck, mpv subtitles via `bridge/`).

When priorities conflict, the higher one wins. First-run setup must stay seamless: one welcome screen, then everything (dictionaries, enrichment pack, sentence model, AnkiDroid note type) installs without manual configuration.

## Sister projects

- **Upstream** (`HuangAntimony/Hoshi-Reader-Android`, iOS reference in `reference/Hoshi-Reader-iOS`): merge regularly. Keep fork code in its own files and packages (`features/setup`, `features/anki/enrich`, …) and touch upstream files only at small hook points, so merges stay easy. For shared reader behavior, check how iOS/upstream does it before diverging.
- **chimahon** (`steindekker/chimahon`): features flow both ways. Planned: chimahon creates the same Hoshi note-type cards as this app, and this app gets chimahon's OCR photo lookup for the dictionary. When porting, follow the other project's working implementation instead of reinventing it.

## Engineering

- Compose + Material 3, Navigation3 per-tab back stacks, Hilt (`@HiltViewModel`, constructor injection), ViewModels exposing `StateFlow` UI state, DataStore-backed settings repositories, WorkManager for work that must survive the app. Blocking work lives behind repositories with an IO dispatcher.
- Current architecture facts are in `docs/ARCHITECTURE.md`; read the relevant part before changing dictionary, reader, Anki, audio, sync or setup code, and update it when those facts change.
- Reader and popup web code lives in `app/src/main/assets/hoshi-web`, not in Kotlin strings. In the popup, all coordinate math under CSS `zoom` goes through `hoshiPopupGeometry`.
- Visible strings are Android resources in both `values/` and `values-zh-rCN/` (lint enforces this). Use Material icons. Accessibility and TalkBack are not targets.
- Fix the cause, not the symptom. Log failures that would otherwise be silent.

## Workflow

- Validate with `docs/VALIDATION.md` (at least `./gradlew test assembleDebug`, plus `lint` for UI/resources, and `node --test app/src/test/js/*.test.mjs` for web assets). Prefer behavior tests over source-string tests.
- Never run tasks that wipe or uninstall app data on a real device; use the emulator for destructive tests. Install on the phone in place (`adb install -r`, same signing key).
- Commit finished work with Conventional Commits. Larger features go on a branch.
- `docs/CHANGELOG.md`: user-visible changes only, under `[Unreleased]`.
- **This repository is public.** Never commit hostnames, private IPs, personal paths, tokens, keys or credentials, in files, tests or commit messages. Deployment values come from environment variables or local files in `~/.config/hoshi-reader/` (for example `HOSHI_SETUP_URL`, `HOSHI_SETUP_TOKEN`); docs use placeholders.
