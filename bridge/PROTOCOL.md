# hoshi-bridge protocol (v2)

Contract between `hoshi-bridge` (this directory) and the Hoshi Reader Android
client (Live tab). Both sides live in this repository and must implement
exactly this. v1 (`/api/*`) is gone; those paths answer `410`. Additive JSON
fields are allowed and clients must ignore unknown fields and message types.
Breaking changes bump `protocol`.

## Model

A **bridge** runs on one machine (the Steam Deck, a PC running mpv, …). It
hosts zero or more **sessions**. A session is one thing producing Japanese
text:

| kind | cues | playback | example |
|---|---|---|---|
| `vn` | appended over time, no timestamps | none | the text hooker log of a visual novel |
| `video` | the whole subtitle track, sent up front | yes | a file playing in mpv |
| `web` | like `video` (reserved for browser producers) | optional | a userscript via ingest |

Each session has a **cue track** identified by `trackId`. Cue ids are only
meaningful within one track: when the subtitle track, file, or `sub-delay`
changes, the bridge sends a new `trackId` and the client replaces its cues.
A card references `(sessionId, trackId, cueId)`; the bridge resolves the media
times from its current track.

Capabilities a session may advertise: `control` (pause/play/seek),
`screenshot` (a still JPEG), `audio` (sentence audio as MP3), `clip` (an
animated WebP of the cue). `vn` sessions on the Deck offer `screenshot`;
mpv offers all four; ingest sessions offer none.

## Transport

- Plain HTTP + WebSocket on one port, default **7277**, bound to `0.0.0.0`
  (reached over Tailscale, e.g. `http://steamdeck:7277`).
- All JSON is UTF-8. Timestamps (`at`, `activeAt`, `now`) are Unix epoch
  **milliseconds**; media positions (`startMs`, `positionMs`, …) are
  milliseconds from the start of the media.
- Errors: non-2xx status with body `{"error": "<human readable message>"}`.
- Optional auth: if the bridge has a token, every `/v2/*` request must send
  `Authorization: Bearer <token>`; the WebSocket upgrade may instead pass
  `?token=<token>`. Missing/wrong token → `401`. `GET /` (debug page) is open.

## Objects

### Cue

```json
{"id": 12, "text": "「行くぞ」", "startMs": 61230, "endMs": 63010, "at": 1759600000000}
```

- `id`: int64, unique and stable within its track. For `vn` it is the
  persisted line id (strictly increasing, survives restarts).
- `text`: never empty. May contain `\n` (multi-line subtitles).
- `startMs` / `endMs`: present for timed cues (`video`, `web`), already
  shifted by the player's subtitle delay. Absent for `vn`.
- `at`: when the bridge received the line (`vn`, live fallback cues).

### Playback

```json
{"positionMs": 61500, "paused": false, "speed": 1.0, "at": 1759600000000}
```

`at` is when the bridge sampled `positionMs`. While playing, the client
extrapolates `positionMs + (now - received) * speed` (use the local receipt
time rather than `at` to avoid clock skew between machines).

### Session

```json
{
  "id": "mpv",
  "kind": "video",
  "title": "Sousou no Frieren - 01",
  "source": "mpv",
  "capabilities": ["control", "screenshot", "audio", "clip"],
  "trackId": "3f9a12c0",
  "activeAt": 1759600000000
}
```

- `id`: unique per bridge (`vn`, `mpv`, or an ingest id).
- `title`: display title; may be `""` (the client picks a fallback, e.g. the
  host name).
- `source`: `texthooker`, `mpv`, or `ingest`.
- `activeAt`: last activity (new cues, play/pause/seek, playing). Clients
  rank sessions across bridges by activity; convert with `hello.now` to
  compensate for clock skew.

### Bridge

```json
{
  "name": "hoshi-bridge",
  "version": "0.2.0",
  "protocol": 2,
  "hostname": "steamdeck",
  "sources": [
    {"id": "lunahook", "kind": "hook", "target": "LunaHook", "connected": true, "lastError": null},
    {"id": "mpv", "kind": "mpv", "target": "/tmp/mpvsocket", "connected": false, "lastError": "no socket"}
  ],
  "screenshot": {"available": true, "backend": "gamescope"},
  "hook": {"enabled": true, "state": "attached", "game": "sgre_steam.exe", "pid": 18927,
           "selectedThreadId": "324-6ffffcd0ef00-1-2", "mode": "auto", "engine": "_5pb_2"}
}
```

`sources[].kind` is one of `http`, `hook`, `websocket`, `clipboard`, `mpv`.
`hook.state` is `idle`, `injecting`, `attached` or `error`; `game`/`pid` are
`null` when idle; `error`/`engine` appear only when known.

## WebSocket `GET /v2/ws`

Server → client text frames, one JSON object each with `type`. Client →
server frames are ignored (reserved). The server sends ping control frames
every 20 s and drops clients that stop answering; a client that falls behind
is closed with status 1013 and should reconnect.

- **`hello`** — exactly once, first:
  `{"type": "hello", "protocol": 2, "now": <ms>, "bridge": {Bridge}, "sessions": [Snapshot...]}`.
  A snapshot is a Session plus `"cues": [Cue...]` (the whole track for
  `video`/`web`, the last 200 cues for `vn`; always an array) and
  `"playback": {Playback}` when known. Every (re)connect gets a full
  snapshot; there is no gap filling. The event stream continues exactly where
  the snapshot ends.
- **`session`** — `{"type": "session", "session": {Session}}`: a session
  appeared or its title/capabilities changed. A different `trackId` than the
  client knows means the old cues are obsolete (a `cues` replace follows).
- **`sessionEnded`** — `{"type": "sessionEnded", "sessionId": "mpv"}`.
- **`cues`** — `{"type": "cues", "sessionId": "mpv", "trackId": "…", "mode": "replace"|"append", "cues": [Cue...]}`.
  `replace` swaps the whole track (and sets the session's `trackId`);
  `append` adds cues to the end (deduplicate by id).
- **`playback`** — `{"type": "playback", "sessionId": "mpv", "positionMs": …, "paused": …, "speed": …, "at": …}`
  on every pause/play/seek/speed change and as a heartbeat about every 2 s.
- **`bridge`** — `{"type": "bridge", "bridge": {Bridge}}` when a source
  connects or disconnects, screenshot availability changes, or the hook state
  changes.
- **`threads`** — `{"type": "threads", "mode": …, "selectedThreadId": …, "threads": […]}`,
  same body as `GET /v2/hook/threads`, when the hooked game's thread list or
  selection changes (score-only updates at most every ~2 s).

## HTTP endpoints

### `GET /v2/status`
→ `200 {"bridge": {Bridge}, "sessions": [Session...]}` (no cues). Used for
"test connection".

### `POST /v2/sessions/{id}/control`
Body `{"action": "pause"|"play"|"seek", "ms": <position, seek only>}`.
→ `200 {"ok": true}`. Needs `control`.

### `POST /v2/sessions/{id}/screenshot`
Body (all optional): `{"trackId": "…", "cueId": 12, "atMs": 61500, "maxWidth": 1280, "quality": 85}`.
→ `200 image/jpeg`. Needs `screenshot`.
- `vn`: a screenshot cached right after the cue's line arrived when present
  (`X-Screenshot-Cue-Id` header), otherwise the screen right now.
- `video`: the frame at `atMs`, default the cue's midpoint. When the player
  is paused inside the cue, the bridge grabs the displayed frame from the
  player instead.
- `maxWidth` (default 1280, `0` = original) scales down preserving aspect,
  never up; `quality` 1–100 (default 85). `X-Screenshot-Captured-At: <ms>`
  is set for screen captures.

### `POST /v2/sessions/{id}/audio`
Body: `{"trackId": "…", "cueId": 12, "padMs": 150}` or
`{"startMs": 61000, "endMs": 63000, "padMs": 150}`.
→ `200 audio/mpeg`: the selected audio track from `startMs - padMs` to
`endMs + padMs` (MP3, libmp3lame ~192 kb/s, 48 kHz). `padMs` defaults to 150,
max 2000; ranges are capped at 60 s. Needs `audio`.

### `POST /v2/sessions/{id}/clip`
Body: `{"trackId": "…", "cueId": 12, "maxWidth": 480, "fps": 12}` or
`{"startMs": …, "endMs": …, …}`.
→ `200 image/webp`: a looping animated WebP of the range without audio,
capped at 6 s, `maxWidth` 16–1920 (default 480), `fps` 1–30 (default 12),
tuned to stay well under 1 MB. If the encoder has no animated WebP support
the bridge answers `image/gif` instead. Needs `clip`. Cards embed it with
`<img>` (AnkiDroid, Anki desktop and AnkiMobile animate WebP).

Command errors: `400` bad body, `404` unknown session or cue, `409` the
`trackId` is no longer the current track, `501` the session lacks the
capability, `503` the media could not be produced (message says why).

### `POST /v2/ingest/lines`
Adds a line to the `vn` session (custom hook scripts). Body `text/plain`
(the line) or `application/json` `{"text": "…", "source": "optional-id"}`.
→ `201` Cue, `200 {"duplicate": true}` (same as the previous line) or
`200 {"duplicate": false, "filtered": true}` (dropped by a configured
filter), `400` when empty.

### `POST /v2/ingest/sessions/{id}`
Creates or updates a custom session (`id` matches `[A-Za-z0-9._-]{1,64}`;
`vn` and `mpv` are reserved → `409`). Body:

```json
{"kind": "web", "title": "…", "trackId": "optional", "mode": "replace"|"append",
 "cues": [{"text": "…", "startMs": 0, "endMs": 1200}], "playback": {Playback}}
```

`kind` defaults to `web`, `mode` to `append`. Cues without an `id` get
increasing ids. → `200 {Session}`. Ingest sessions have no capabilities.

### `DELETE /v2/ingest/sessions/{id}`
Ends a custom session. → `200 {"ok": true}`.

### `GET /v2/hook/threads`
→ `200` the text threads of the hooked game (Steam Deck, LunaHook):

```json
{
  "mode": "auto",
  "selectedThreadId": "324-6ffffcd0ef00-1-2",
  "threads": [
    {"id": "324-6ffffcd0ef00-1-2", "name": "render", "hookCode": "HVXF-24:-54@3EF00:sgre_steam.exe",
     "process": "sgre_steam.exe", "score": 0.91, "selected": true, "remembered": false,
     "outputCount": 4, "lastOutputAt": 1759600000000,
     "samples": [{"text": "「おはよう」", "at": 1759600000000}]}
  ]
}
```

### `POST /v2/hook/threads/select`
Body `{"threadId": "<id>"}` pins a thread; `{"threadId": null}` returns to
automatic selection and forgets the pin for this game. → `200` as above;
`404` unknown id; `409` hooking disabled; `400` bad body. The choice is
remembered per executable.

### `GET /`
Human-readable debug page (live sessions, cues, media buttons, hook threads).
Not used by the Android client.
