# Hoshi Reader (Stein's fork)

A personal fork of Hoshi Reader Android (Kotlin, Jetpack Compose). It follows its own product direction; upstream is something to merge from, not a spec to match.

## What matters, in order

1. **Dictionary**: fast, rich lookups; the main reason the app exists.
2. **Anki card creation**: a good card with no setup, the "omakase" path. One tap opens "Add to Anki", a long press adds instantly, and the card lands in the shipped Hoshi note type with definitions, pitch, frequency, example sentences, audio and a pitch-marked sentence filled in.
3. **Reading**: books, and Live (visual novels on the Steam Deck, mpv subtitles via `bridge/`).

When priorities conflict, the higher one wins. First-run setup must stay seamless: one welcome screen, then everything (dictionaries, enrichment pack, sentence model, AnkiDroid note type) installs without manual configuration.

## Sister projects

- **Upstream** (`HuangAntimony/Hoshi-Reader-Android`, iOS reference in `reference/Hoshi-Reader-iOS`): merge regularly. Keep fork code in its own files and packages (`features/setup`, `features/anki/enrich`, …) and touch upstream files only at small hook points, so merges stay easy. For shared reader behavior, check how iOS/upstream does it before diverging. Upstream's `docs/` (architecture, validation, plans) was removed here; on merge, resolve modify/delete conflicts there with `git rm`.
- **hoshi-card** (`third_party/hoshi-card`, or a sibling `../hoshi-card`): the card-enrichment library shared with chimahon.
- **chimahon** (`steindekker/chimahon`): features flow both ways. Planned: chimahon creates the same Hoshi note-type cards as this app, and this app gets chimahon's OCR photo lookup for the dictionary. When porting, follow the other project's working implementation instead of reinventing it.

Fork-specific internals (setup, hoshi-card, enrichment pack, Live) are summarized in `docs/FORK.md`. Everything else: read the code.

## Engineering

- Compose + Material 3, Navigation3 per-tab back stacks, Hilt, ViewModels exposing `StateFlow`, DataStore settings repositories, WorkManager for work that must survive the app. Blocking work lives behind repositories on an IO dispatcher.
- Reader and popup web code lives in `app/src/main/assets/hoshi-web`, not in Kotlin strings. In the popup, all coordinate math under CSS `zoom` goes through `hoshiPopupGeometry`.
- Visible strings are Android resources in both `values/` and `values-zh-rCN/` (lint enforces this; when two entries share an English value, give every duplicate a `comment`). Use Material icons. Give icon-only controls a `Modifier.testTag` so they are findable in the UI tree.
- Fix the cause, not the symptom. Log failures that would otherwise be silent.

## Validate

- Always: `./gradlew test assembleDebug`. Add `./gradlew lint` for UI, resources, manifest or packaging; `node --test app/src/test/js/*.test.mjs` for web assets; `go vet ./... && go test ./...` in `bridge/` for the bridge; `./gradlew :app:assembleRelease` for SDK, ABI, signing, R8 or packaging changes.
- One JVM test: `./gradlew :app:testDebugUnitTest --tests <class>[.<method>]` (the `test` aggregate doesn't take `--tests`).
- Prefer behavior tests over source-string tests.
- Transcription runtime (FFmpeg component): follow the docstrings in `tools/build-transcription-audio.py` and `tools/publish-transcription-components.py`.

## On a device

Read the UI as text; screenshots are for visual checks only, never for finding what to tap.

- `tools/emulator.sh start|install|launch` drives the headless `hoshi-api36` emulator (debug package `nl.steindekker.hoshi.debug`).
- Native UI: `tools/emulator.sh ui` lists `text | desc | testTag | x,y` (`*` = clickable); tap with `adb shell input tap x y`.
- WebView UI (dictionary results, reader, lookup popup; the dictionary sits in an iframe): `tools/emulator.sh webview`, then `tools/awv snap`, `find '<text>'`, `click '<selector>'`, `text`, `eval`. `awv wait 'text=…'` replaces `sleep`. Coordinates it prints are device pixels.
- Check results directly: `adb pull /sdcard/AnkiDroid/collection.anki2` for created notes, `adb logcat --pid`, `run-as` for app files.
- Typing: `adb shell input text` goes through Japanese Gboard. Send romaji a key at a time; Enter once confirms the composition and a second Enter submits.
- Never wipe, clear or uninstall app data on a real device, and don't run `connected*AndroidTest` there (it reinstalls). Install in place with `adb install -r` (same signing key). For an instrumented test, install both APKs with `adb install -r` and run `adb shell am instrument -w -e class <class> nl.steindekker.hoshi.debug.test/androidx.test.runner.AndroidJUnitRunner`; it passes only on a final `OK (N tests)`. Use the emulator for anything destructive.

## Workflow

- Commit finished work with Conventional Commits. Larger features go on a branch.
- `docs/CHANGELOG.md`: user-visible changes only, under `[Unreleased]`.
- **This repository is public.** Never commit hostnames, private IPs, personal paths, tokens, keys or credentials, in files, tests or commit messages. Deployment values come from environment variables or local files in `~/.config/hoshi-reader/` (for example `HOSHI_SETUP_URL`, `HOSHI_SETUP_TOKEN`); docs use placeholders.
