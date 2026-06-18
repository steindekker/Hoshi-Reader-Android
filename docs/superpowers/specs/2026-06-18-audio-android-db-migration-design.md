# Term-audio migration to the android.db format

**Date:** 2026-06-18
**Status:** approved (design)

## Why

The hand-built term-audio store (`var/audio.sqlite`, ~2.0 GB) is transcoded from the
locally-installed `local-audio-yomichan` add-on, so its coverage is whatever that
add-on happened to hold. A complete prebuilt store exists: `android.db` — the
standard `local-audio-yomichan` / AnkiConnect-Android packaged export (the same
ecosystem, the full distribution). It is self-contained (blobs embedded), strictly
larger in coverage, and is the format Hoshi Reader and AnkiConnect-Android already
consume.

Adopt `android.db` as-is and replicate Hoshi Reader's lookup behaviour exactly.

### Coverage (android.db vs current store)

| source | current | android.db |
|---|---|---|
| jpod | 191,448 | 199,110 |
| shinmeikai8 | 173,702 | 175,534 |
| nhk16 | 146,425 | 150,162 |
| forvo | 78,835 | 85,791 |
| daijisen | — | 165,345 |
| taas | — | 48,994 |
| ozk5 | — | 36,496 |
| forvo_ext (+ext2) | — | 16,032 |

877,464 entries / 472,955 blobs / 6.2 GB; blob coverage complete (2 orphans).

## Decisions

- **Ship the full 6.2 GB android.db as-is** — no filtering. It rides to Hetzner like
  the rest of `var/`.
- **Use every source**, ordered exactly as Hoshi Reader does.
- **Drop the re-pitch pass entirely** — Hoshi has no equivalent and backfill already
  prefers the accent dictionaries first. This also retires the broken `.opus` "ours"
  marker (android.db clips are all `.mp3`).
- **No forvo speaker preference** — match Hoshi (rank by source only).
- Source android.db located at `dicts/audio/android.db`, overridable via
  `ANKIENRICH_AUDIO_DB`; hardlinked (fallback copy) into `var/audio.sqlite`.

## Schema (android.db — read-only at runtime)

```
entries(id INTEGER PK, expression TEXT, reading TEXT, source TEXT,
        speaker TEXT, display TEXT, file TEXT)
android(id INTEGER PK, file TEXT, source TEXT, data BLOB)   -- clip bytes
```

Indexes already present: `idx_all(expression, reading, source)`,
`idx_android(file, source)`, etc. — cover all our queries. `id` and `display` are
ignored. The runtime never writes to this DB.

## Components

### `audio/build.py` — locate & place (no transcode)

Delete `locate_addon`, the `entries`/`clips` transcode `build()`, and the add-on
schema. `ensure(db_path=None) -> str`:

- `var/audio.sqlite` exists → `"present"`.
- else source android.db found (`$ANKIENRICH_AUDIO_DB`, else `dicts/audio/android.db`)
  → `os.link()` into `var/audio.sqlite` (fallback `shutil.copyfile` on cross-device
  `OSError`) → `"built"`.
- else → `"no-source"`.

No `aqt` import; `config.LOCAL_AUDIO_ADDON_ID` removed.

### `audio/enrich.py` — Hoshi-port lookup

```
SOURCE_PRIORITY = ["nhk16", "daijisen", "shinmeikai8", "jpod", "jpod_alternate",
                   "taas", "ozk5", "forvo", "forvo_ext", "forvo_ext2"]
SUPPORTED_EXT   = {"mp3", "opus", "ogg"}
```

`lookup(conn, term, reading) -> Pick | None` (ports `LocalAudioResolver.resolve`):

1. `term = _clean(term)`; `reading = kata2hira(_clean(reading))`.
2. SQL: if reading blank → `WHERE expression=?` (args `term`); else
   `WHERE (expression=? OR reading=?)` (args `term, reading`). Select
   `source, expression, reading, file`.
3. Drop rows whose `file` extension ∉ `SUPPORTED_EXT`.
4. Sort by three keys, take first → `Pick(source, file)`:
   1. reading-match: `0 if (reading and row.reading == reading) else 1`,
   2. source rank: index in `SOURCE_PRIORITY`, or `len(SOURCE_PRIORITY)` (last) if absent,
   3. `source` name (alphabetical tiebreak).

`read_clip(conn, pick) -> bytes | None`:
`SELECT data FROM android WHERE source=? AND file=? LIMIT 1`.

`Pick` and `media_name` unchanged (`la_<source>_<basename(file)>`). `speaker` on
`Pick` becomes vestigial (always None from `lookup`); keep the field for API
stability.

**Removed:** `lookup_dict`, `select_repitch_targets`, `plan_repitch`, `run_repitch`,
`DICT_SOURCES`, `FORVO_SPEAKER`, `FILL_ORDER`. `SOURCE_LABELS` kept only if still
referenced for reporting; otherwise removed.

`select_targets`, `plan_backfill`, `run_backfill` keep their signatures — they call
the new `lookup`/`read_clip`. `plan_backfill` keeps classifying via `read_clip`
presence (now ~always present).

### `audio/index.py` — unchanged

Read-only `open_index(path)` over `var/audio.sqlite`.

### `pipeline.py` — `_step_audio`

Drop `run_repitch`/`plan_repitch` calls and the `upgraded`/`will_upgrade`/
`upgrade_errors`/`no_dict` keys. Backfill reporting unchanged. `audio_build.ensure()`
contract (`present`/`built`/`no-source`) unchanged, so the skip path holds.

### `bot.py`, `mine.py`, `enrich_one.py` — no changes

Same `audio_index.open_index()`, `audio_enrich.lookup`, `read_clip`, `media_name`,
`Pick`.

## Data flow (mining / backfill)

`term, reading → lookup(conn) → Pick → read_clip(conn) → bytes →
media.write_data(media_name) → [sound:…]`. Identical to today; only the store schema
and ranking changed.

## Error handling

- Missing store / no source → pass skipped (`no-source`), as today.
- Per-card error isolation in `run_backfill` retained.
- Cross-device hardlink failure → copy fallback.

## Testing

- Rewrite `tests/test_audio.py`: build a tiny fixture DB in the **android.db schema**
  (`entries` + `android`, `.mp3` files). Cover: reading-match beats expression-only
  across sources; `SOURCE_PRIORITY` ordering incl. daijisen and the new sources;
  unsupported-extension filtering; reading-blank path; `read_clip` from `android`;
  no-hit → None. Delete all re-pitch and `lookup_dict` tests.
- Rewrite `tests/test_audio_build.py`: `ensure()` present / hardlink-from-source /
  no-source paths.
- `tests/test_pipeline.py`: update the audio stub to the new `ensure`/`run_backfill`
  surface (no repitch).
- TDD via the writing-plans step.

## Docs

Update `CLAUDE.md`, `AGENTS.md`, `README.md`: term-audio source is the android.db
artifact (`dicts/audio/android.db`), not the add-on; re-pitch pass removed.

## Out of scope (YAGNI)

Configurable per-user source order (Hoshi's `LocalAudioSourceConfig` JSON), the
`display` accent column, mp3→opus transcoding, `poc/` (reference-only, untouched).
