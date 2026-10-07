# steamdeck-vn-extractor protocol (v1)

Contract between the Steam Deck server (`vn-extractor`, this repo) and the
Hoshi Reader Android client (VN tab). Both sides must implement exactly this.
Bump `protocol` only for breaking changes; additive JSON fields are allowed and
clients must ignore unknown fields.

## Transport

- Plain HTTP + WebSocket on one port, default **7277**, bound to `0.0.0.0`
  (reached over Tailscale, e.g. `http://steamdeck:7277`).
- All JSON is UTF-8. Timestamps are Unix epoch **milliseconds** (int64).
- Errors: non-2xx status with body `{"error": "<human readable message>"}`.
- Optional auth: if the server is configured with a token, every `/api/*`
  request must send `Authorization: Bearer <token>` (the WebSocket upgrade may
  instead pass `?token=<token>`). Missing/wrong token → `401`.

## Line object

```json
{
  "id": 42,
  "text": "「おはよう、今日もいい天気だね」",
  "source": "textractor",
  "receivedAt": 1759600000000,
  "hasScreenshot": false
}
```

- `id`: int64, strictly increasing, survives server restarts (log is persisted).
- `text`: trimmed, never empty. Consecutive duplicates are dropped server-side.
- `source`: id of the configured source that produced the line (`"http"` for
  `POST /api/lines`, `"lunahook"` for text captured by automatic hooking).
- `hasScreenshot`: true when the server holds a cached screenshot captured
  right after this line arrived (optional server feature, see screenshots).

## Status object

```json
{
  "server": "steamdeck-vn-extractor",
  "version": "0.1.0",
  "protocol": 1,
  "hostname": "steamdeck",
  "latestLineId": 42,
  "sources": [
    {"id": "textractor", "kind": "websocket", "target": "ws://127.0.0.1:6677",
     "connected": true, "lastError": null}
  ],
  "screenshot": {"available": true, "backend": "gamescope"},
  "hook": {
    "enabled": true,
    "state": "attached",
    "game": "sgre_steam.exe",
    "pid": 18927,
    "selectedThreadId": "324-6ffffcd0ef00-1-2",
    "mode": "auto",
    "engine": "_5pb_2"
  }
}
```

`latestLineId` is `0` when no lines exist. `kind` is one of
`websocket`, `clipboard`, `http`, `hook`. `screenshot.backend` is a short name or `null`.

`hook` describes automatic LunaHook injection. `state` is `idle` (no game, or
hooking is off), `injecting`, `attached`, or `error`. `game` is the Windows
executable name and `pid` is its Linux pid; both are `null` when idle.
`selectedThreadId` is the thread currently producing lines, or `null`.
`mode` is `auto` or `manual`. `error` is present only when the last attempt
failed. `engine` is present after LunaHook names the game engine. Clients that
do not care about hooking ignore `hook`.

The built-in `lunahook` source (`kind: "hook"`) is `connected` only while
`hook.state` is `attached`. It is not a configured `sources` entry.

## Endpoints

### `GET /api/status`
→ `200` status object.

### `GET /api/lines?after=<id>&limit=<n>`
→ `200 {"lines": [Line...], "latestLineId": 42}`, ascending by id.
- `limit` defaults to 100, max 1000.
- Without `after`: the last `limit` lines. With `after`: the first `limit`
  lines with `id > after`.

### `POST /api/lines`
Ingest a line from a custom hook script.
Body: `text/plain` (the line) or `application/json` `{"text": "...", "source": "optional-id"}`.
→ `201` Line object, or `200 {"duplicate": true}` when deduplicated,
or `400` when empty.

### `GET /api/ws[?after=<id>]` (WebSocket)
Server → client text frames, each one JSON object with `type`:

- On connect, exactly one
  `{"type": "hello", "protocol": 1, "status": {Status}, "lines": [Line...]}`
  where `lines` = the last 50 lines, or, if `after` is given, lines with
  `id > after` (capped at 1000) so a reconnecting client can fill gaps.
- `{"type": "line", "line": {Line}}` for every new line, in id order.
- `{"type": "status", "status": {Status}}` whenever a source connects or
  disconnects or screenshot availability changes.

The server sends WebSocket ping control frames every 20 s and drops clients
that stop answering. Client → server frames are ignored (reserved).

### `GET /api/hook/threads`
→ `200` the text threads of the hooked game:

```json
{
  "mode": "auto",
  "selectedThreadId": "324-6ffffcd0ef00-1-2",
  "threads": [
    {
      "id": "324-6ffffcd0ef00-1-2",
      "name": "render",
      "hookCode": "HVXF-24:-54@3EF00:sgre_steam.exe",
      "process": "sgre_steam.exe",
      "score": 0.91,
      "selected": true,
      "remembered": false,
      "outputCount": 4,
      "lastOutputAt": 1759600000000,
      "samples": [{"text": "「おはよう」", "at": 1759600000000}]
    }
  ]
}
```

`selectedThreadId` is `null` when nothing is selected. `threads` is `[]` when
no game is hooked. `lastOutputAt` is `null` until the thread speaks.
Samples are the most recent cleaned sentences (at most 5), oldest first.

### `POST /api/hook/threads/select`
Body: `{"threadId": "<id>"}` to pin a thread, or `{"threadId": null}` to
return to automatic selection and forget a manual pin for this game.
→ `200` the same object as `GET /api/hook/threads`.
`404` when the id is not in the current list. `409` when hooking is disabled.
`400` when the body is not that object.

The choice is remembered per executable. The next session of the same game
starts on that thread until the client sends `null`.

### WebSocket `threads`

`{"type": "threads", "mode": "auto", "selectedThreadId": null, "threads": []}`
is sent when the thread list or the selection changes. Score-only updates are
throttled to about one message every 2 seconds. The fields match
`GET /api/hook/threads`.

### `POST /api/screenshot?lineId=<id>&maxWidth=<px>&quality=<1-100>`
Returns a screenshot of the game as `image/jpeg`.
- If `lineId` is given and a cached screenshot for that line exists, that one is
  returned; otherwise the screen is captured now.
- `maxWidth` (default 1280, `0` = no downscale) scales down preserving aspect
  ratio; never upscales. `quality` defaults to 85.
- Response headers: `X-Screenshot-Captured-At: <epoch ms>`, and
  `X-Screenshot-Line-Id: <id>` when a cached per-line screenshot was returned.
- `503 {"error": ...}` when no screenshot backend works / capture failed.

### `GET /`
Small human-readable HTML page (live log + status) for debugging in a browser.
Not used by the Android client.
