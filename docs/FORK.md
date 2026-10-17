# Fork internals

Facts about fork-only code that aren't obvious from one file. Keep this short; update it when one of these changes.

## Setup (`features/setup`)

- A full-screen welcome downloads the Yomitan dictionaries, the sentence model and the enrichment pack from the catalog at `BuildConfig.HOSHI_SETUP_URL` (env `HOSHI_SETUP_URL` or `~/.config/hoshi-reader/setup-url`; without one there is no welcome). Requests to that host alone send `X-Hoshi-Token` (`SetupServerConnections`; env `HOSHI_SETUP_TOKEN` or `~/.config/hoshi-reader/setup-token`). Releases get both from repository secrets.
- Setup asks for the AnkiDroid permission first. `SetupAnkiConnector` stores the real deck and note-type ids and creates the `Hoshi` note type from hoshi-card when missing. The Hoshi mining format is written only while every card format still has empty mappings; existing mappings are kept and upstream's `AnkiRepository.fetchConfiguration` is untouched.
- Offline audio is opt-in: `SetupAudioWorker` downloads the catalog's `android.db` as a foreground WorkManager job. The catalog's `audioServer` is inserted right after it, so words still stream when the clip is missing.
- `SetupVersion` (now 3) is bumped when an install must rerun; older installs then see the welcome once more.

## hoshi-card (cards shared with chimahon)

- Gradle includes `:hoshi-card` from `../hoshi-card` when present, else `third_party/hoshi-card`. The library owns the catalog model and verified downloads, the enrichment pack, the on-device JRP sentence model with Sudachi (R8 rules in its `consumer-rules.pro`; after changing them, check `hoshi/card/jrp/sudachi/char.def` is in the release APK) and the Hoshi note type. The app keeps adapters only (`features/anki/enrich/HoshiCardModule`, pack URL, token opener, setup UI and worker).
- Its handlebars: `{definition}`, `{definition-source}`, `{pitch-graph}`, `{frequency-rank}`, `{example-sentences}` (from the pack) and `{sentence-pitch}` (computed on the device). `CardEnrichment.protect` swaps them for placeholders before upstream's renderer runs and `fill` restores them, so field HTML is never re-parsed. Without a pack row, the first four fall back to the installed dictionaries.
- The pack is format 2 (built by hoshi-suite's `server/pack-builder`): SQLite, zlib rows keyed by (term, reading) with exactly the five pack keys, reading-independent `fallback_keys`. Any other format counts as not installed. Pack and model live under `files/enrich`. The Add to Anki sheet lists the pack's example sentences before Massif results.

## Live (`features/live`, `bridge/`)

- `hoshi-bridge` (Go, `bridge/`) runs on the Steam Deck (text hooker, screenshots) or a PC (mpv). `bridge/PROTOCOL.md` (v2) is the contract: sessions (`vn`, `video`, `web`) with cue tracks; every WebSocket connect starts with a full `hello` snapshot; media and control are HTTP calls per session.
- `LiveRepository` keeps one WebSocket per host only while the tab is visible, with per-host backoff. `LiveViewModel` shows the pinned or most recently active session; the focused cue is the Dictionary's embedded root, so lookups, audio and Anki reuse the dictionary path. A root lookup pauses playback when allowed.
- Mining from a cue: `AnkiLiveMediaSource` supplies `{image}` (picked web image, then Live picture, then cover; animated WebP for video cues) and `{sentence-audio}` (cue audio, else the Sasayaki cue). Media failures never block the note; they come back as Snackbar warnings.
