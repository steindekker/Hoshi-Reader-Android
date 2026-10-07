# steamdeck-vn-extractor

`vn-extractor` is a small daemon for the Steam Deck that collects the sentences
your text hooker extracts from a Japanese visual novel, keeps them in a log,
and pushes them live to your phone (Hoshi Reader's VN tab) over Tailscale.
When you mine a card on the phone, the phone asks the Deck for a screenshot of
the game for the card's image field.

```
 VN (Proton/Wine) ─► LunaHook, injected automatically ─► vn-extractor ─► phone
                                                              │  :7277 HTTP + WebSocket
                                                              └─ screenshots via gamescope / KWin
```

- Single static binary (Go, no cgo). Everything lives in the `deck` user's home
  directory, so SteamOS's read-only root and OS updates don't matter and no
  packages or `sudo` are needed.
- Text comes from LunaHook, which the daemon injects into whatever Proton game
  you launch. Textractor, LunaTranslator, the clipboard, and `POST /api/lines`
  remain available as optional sources.
- Screenshots work in Game Mode (talks to gamescope directly) and Desktop Mode
  (Spectacle), with auto-detection and a custom-command escape hatch.
- The API is specified in [PROTOCOL.md](PROTOCOL.md).

## Install on the Deck

You need the release tarball on the Deck. Build it on any Linux/macOS machine
with Go 1.26+:

```sh
make release          # dist/vn-extractor + dist/vn-extractor-<ver>-linux-amd64.tar.gz
```

Copy it over and install (Tailscale MagicDNS name `steamdeck` shown; enable
SSH on the Deck first: Desktop Mode → `passwd`, then `sudo systemctl enable --now sshd`,
or use Tailscale SSH):

```sh
scp dist/vn-extractor-*-linux-amd64.tar.gz deck@steamdeck:/tmp/
ssh deck@steamdeck 'cd /tmp && tar xzf vn-extractor-*-linux-amd64.tar.gz && ./vn-extractor-*-linux-amd64/install.sh'
# or simply: make deploy DECK=deck@steamdeck
```

Without SSH, copy the tarball any other way (USB stick, KDE Connect, a browser
download), then in Desktop Mode open Konsole, extract it and run `./install.sh`.

`install.sh` puts the binary in `~/.local/bin/vn-extractor`, writes
`~/.config/steamdeck-vn-extractor/config.json` if it does not exist, installs
and starts the systemd **user** unit `~/.config/systemd/user/vn-extractor.service`.
Re-running it upgrades in place; `./install.sh --uninstall` removes it (config
and log are kept).

**Lingering.** SteamOS logs `deck` in automatically in both modes, so the
service runs whenever the Deck is on. To keep it alive across Game Mode ↔
Desktop Mode switches (the user session is torn down in between), enable
lingering once: `loginctl enable-linger deck` (prefix `sudo` if it asks for
authentication).

Check it:

```sh
systemctl --user status vn-extractor
journalctl --user -u vn-extractor -f
curl -s http://127.0.0.1:7277/api/status
vn-extractor -detect                       # which screenshot backends work right now
vn-extractor -screenshot-test /tmp/t.jpg   # take one screenshot and report the backend
```

Then point the phone at `http://steamdeck:7277` (or the Deck's `100.x.y.z`
Tailscale IP). Open the same URL in a browser for a live debug page.

## Use

1. Install once, as above. `install.sh` also downloads the pinned LunaHook
   DLLs (LunaTranslator v12.0.0) into
   `~/.local/share/steamdeck-vn-extractor/lunahook/`.
2. On the phone, open Hoshi Reader's **VN** tab. The host is `steamdeck`,
   port `7277`. Tailscale has to be connected on the phone and the Deck.
3. Start the visual novel from Steam. The Deck attaches by itself a few
   seconds after the game process appears. Advance one line of dialogue and
   the sentence shows on the phone.
4. Tap a word to look it up, then mine a card. The card's image is a
   screenshot of the game.

There is no Textractor window, no Attach button, and no thread you have to
pick. The daemon follows the thread that looks like dialogue and remembers
that choice for the next session of the same executable. If it follows the
wrong one, open `http://steamdeck:7277/` and press **use** on the right
thread, or `POST /api/hook/threads/select` (see [PROTOCOL.md](PROTOCOL.md)).
**automatic** returns to scoring and forgets the pin.

To turn this off, set `"hook": {"enabled": false}` in the config and restart
the service. A game that is already running is picked up after the next
restart; you do not have to relaunch it.

Tailscale on the Deck is usually installed with the community
[deck-tailscale](https://github.com/tailscale-dev/deck-tailscale) script. If
you also want a token (recommended if other people share your tailnet), set
`"token"` in the config and enter it in the app.

## Configuration

`~/.config/steamdeck-vn-extractor/config.json` (created on first run;
restart the service after editing: `systemctl --user restart vn-extractor`).
Unknown keys are rejected so typos don't go unnoticed. Command-line flags
override the file: `-config`, `-listen`, `-token`, `-data-dir`, `-log-level`.

```jsonc
{
  "listen": "0.0.0.0:7277",        // reachable over Tailscale; use 127.0.0.1:7277 + `tailscale serve` to restrict
  "token": "",                     // if set: every /api/* request needs "Authorization: Bearer <token>"
  "dataDir": "~/.local/share/steamdeck-vn-extractor",   // lines.jsonl lives here
  "maxLines": 5000,                // lines retained (older ones are compacted away; ids keep increasing)
  "dedupe": true,                  // drop a line identical to the previous one
  "logLevel": "info",              // debug logs every line and screenshot
  "allowedOrigins": [],            // extra browser origins allowed to open /api/ws (e.g. "localhost:5173")
  "filters": {
    "drop": [],                    // regexes; a line matching any is discarded, e.g. "^\\d+$"
    "strip": [],                   // regexes removed from the text, e.g. "<[^>]+>" or "【[^】]*】"
    "joinLines": true,             // remove line breaks inside a line (text-box wrapping)
    "maxLineLength": 1000          // longer lines are discarded as hook garbage (0 = no limit)
  },
  "sources": [
    {"id": "textractor", "kind": "websocket", "enabled": false, "url": "ws://127.0.0.1:6677"},
    {"id": "lunatranslator", "kind": "websocket", "enabled": false, "url": "ws://127.0.0.1:2333/api/ws/text/origin"},
    {"id": "clipboard", "kind": "clipboard", "enabled": false, "command": "", "intervalMs": 500}
  ],
  "hook": {
    "enabled": true,               // inject LunaHook into Proton games
    "allow": [],                   // exe globs; empty = every game that is not denied
    "deny": [],                    // extra exe names never hooked (steam.exe and friends are always skipped)
    "codePage": 932,               // Shift-JIS, used by hooks that are not already Unicode
    "flushMs": 500,                // silence before a thread's buffer becomes one sentence
    "scanIntervalMs": 2000
  },
  "screenshot": {
    "enabled": true,
    "backends": [],                // order to try; [] = auto (see below)
    "command": "",                 // custom capture command, must write an image to {output}
    "timeoutMs": 5000,             // per backend attempt
    "gamescopeType": 1,            // 1 game only, 2 game+overlays, 3 full composition, 4 screen buffer
    "onLine": false,               // also capture right after every new line...
    "onLineDelayMs": 300,          // ...after this delay (lets the text box finish drawing)
    "cacheSize": 20,               // per-line screenshots kept in memory
    "env": {}                      // extra environment for external capture commands
  }
}
```

The file is plain JSON; the comments above are documentation only.

Each source runs independently, reconnects with backoff, and reports
`connected` / `lastError` in `/api/status`. Text is trimmed, empty lines are
dropped, and consecutive duplicates (e.g. a hook firing twice) are dropped.

### Automatic hooking

With `"hook": {"enabled": true}` (the default) the daemon watches `/proc` for
a Windows executable running under Proton. Steam, Wine services, installers
and crash handlers are ignored. When a game has been running for a few
seconds, the daemon starts `vnhook.exe` with that game's Proton Wine, in the
same prefix, so it joins the wineserver that is already running. LunaHook's
engine detection inserts the game's text hook (Steins;Gate is a MAGES/5pb
game and is recognized as `_5pb_2`). Sentences from the chosen thread are
stored with source id `lunahook`.

The helper and the DLLs live in `dataDir/lunahook/`. DLLs are checked against
the hashes pinned in the source and downloaded from the LunaTranslator
v12.0.0 release when they are missing. `vn-extractor -setup` does that
download on its own. Nothing under this directory is committed to the repo.

`/api/status` includes a `hook` object (`idle`, `injecting`, `attached`,
`error`). While the state is `attached`, the `lunahook` source reports
`connected`, which is what the phone uses to decide that a text source is
online.

### Textractor (Proton/Wine)

Install a WebSocket extension into Textractor's extension list. Wine sockets
are real Linux sockets, so the native daemon can connect to the Windows
program on `127.0.0.1`. All common extensions listen on port **6677** and send
each sentence as a **plain-text** frame:

| Extension | Endpoint | Notes |
|---|---|---|
| [kuroahna/textractor_websocket](https://github.com/kuroahna/textractor_websocket) | `ws://localhost:6677` | plain text |
| [sadolit/textractor-websocket](https://github.com/sadolit/textractor-websocket) / [AuroraWright fork](https://github.com/AuroraWright/textractor-websocket) | `ws://0.0.0.0:6677` | plain text; starts lazily — the server only comes up after you select a hook thread and one line is captured, so `textractor` shows *disconnected* until then. |

The default config already has `{"id":"textractor","kind":"websocket","url":"ws://127.0.0.1:6677"}`.
JSON frames are also accepted: if a frame is a JSON object, the first string
field among `sentence`, `text`, `line`, `message`, `data` is used.

### LunaTranslator

Enable *Network service* (网络服务) in LunaTranslator's settings (default port
2333). Its `/api/ws/text/origin` WebSocket streams every extracted original
sentence as plain text ([docs](https://github.com/HIllya51/LunaTranslator/blob/main/docs/en/apiservice.md)).
Enable the `lunatranslator` source, or:

```json
{"id": "lunatranslator", "kind": "websocket", "enabled": true, "url": "ws://127.0.0.1:2333/api/ws/text/origin"}
```

### Clipboard hookers

For hookers that copy each sentence to the clipboard (Textractor's built-in
copy-to-clipboard extension, Agent, …) enable the `clipboard` source. With
`"command": ""` (or `"x11"`) the daemon reads the X11 `CLIPBOARD` natively, no
`xclip` needed — useful because SteamOS does not ship `xclip`. Wine games use
X11, and gamescope syncs the clipboard between its Xwayland servers, so this
works in Game Mode; it auto-selects gamescope's Xwayland when present
(override with `"display": ":0"`). In Desktop Mode it uses KWin's Xwayland
and finds its `xauth_*` cookie automatically.

Alternatively run any command that prints the clipboard:

```json
{"id": "clipboard", "kind": "clipboard", "enabled": true, "command": "wl-paste --no-newline", "intervalMs": 500,
 "env": {"WAYLAND_DISPLAY": "wayland-0"}}
```

Content present when the daemon starts is not emitted; only changes are.

### Custom scripts

Anything can push a line:

```sh
curl -X POST --data-binary '「おはよう」' -H 'Content-Type: text/plain' http://127.0.0.1:7277/api/lines
curl -X POST -H 'Content-Type: application/json' -d '{"text":"「おはよう」","source":"myscript"}' http://127.0.0.1:7277/api/lines
```

## Screenshots

`POST /api/screenshot` tries the backends below in order and uses the first
that is available and succeeds. `"backends"` can restrict/reorder them. The
image is decoded, scaled to `maxWidth` and re-encoded as JPEG. `/api/status`
shows the backend that is currently usable (re-checked every 10 s, so it
follows Game Mode ↔ Desktop Mode switches).

| Backend | Mode | How | Needs |
|---|---|---|---|
| `custom` | any | runs `screenshot.command` via `/bin/sh -c`; `{output}` is the (quoted) .png path to write, `{display}` the X display | only when configured |
| `gamescope` | Game Mode | speaks gamescope's private Wayland protocol `gamescope_control` (v3+ `take_screenshot`) on `$XDG_RUNTIME_DIR/gamescope-0`, waits for the `screenshot_taken` event | nothing (built in) |
| `gamescopectl` | Game Mode | `gamescopectl screenshot {output}` (gamescope's own CLI, same mechanism via the `screenshot` debug command) | `gamescopectl` (ships with gamescope) |
| `gamescope-x11` | Game Mode | sets `GAMESCOPECTRL_DEBUG_REQUEST_SCREENSHOT` on the root window of gamescope's Xwayland (found via its `GAMESCOPE_XWAYLAND_SERVER_ID` root property), waits until gamescope deletes it, reads `/tmp/gamescope*.png` | nothing (built in, pure-Go X11) |
| `spectacle` | Desktop Mode | `spectacle --background --nonotify --fullscreen --output {output}` | Spectacle (preinstalled in SteamOS Desktop Mode) |
| `grim` | wlroots compositors | `grim {output}` | grim; not KWin/gamescope |
| `import` | pure X11 sessions | `import -window root {output}` (ImageMagick) | not under Wayland/gamescope (Xwayland root would be black) |
| `ffmpeg` | pure X11 sessions | `ffmpeg -f x11grab -i $DISPLAY -frames:v 1 {output}` | same as above |

Notes on Game Mode:

- gamescope added `take_screenshot` to `gamescope_control` in Dec 2023
  (gamescope 3.13.x) and the `screenshot` console command used by
  `gamescopectl` in Jun 2024, so any current SteamOS should have both; the
  built-in `gamescope` backend only needs the former. gamescope writes the PNG itself, so the capture is the real
  composited frame, typically in under 100 ms. Type `1` ("base plane only")
  is the game at its render resolution without the Steam overlay, keyboard or
  notifications — ideal for cards.
- gamescope does not implement wlr-screencopy, and its Xwayland root window
  is not composited, so `grim`, `import` and `ffmpeg` cannot work there.
- The `gamescope-x11` route uses the *debug* request atom on purpose: the
  non-debug `GAMESCOPECTRL_REQUEST_SCREENSHOT` is the one meant for the Steam
  client's own screenshot feature, and its file name is fixed
  (`/tmp/gamescope.png`), so the Wayland route is preferred.
- Screenshots are written to `$XDG_RUNTIME_DIR/vn-extractor/` (a per-user
  tmpfs) and deleted after reading.

With `"onLine": true` the daemon captures a screenshot ~300 ms after each new
line (debounced: while you skip text quickly only the last line is captured)
and keeps the last `cacheSize` of them in memory (lost on restart). Lines with a cached screenshot have
`hasScreenshot: true`, and `POST /api/screenshot?lineId=<id>` returns that
exact screenshot even if you mine the sentence later.

### Troubleshooting

- `vn-extractor -detect` lists every backend and why it is unavailable;
  `vn-extractor -screenshot-test /tmp/t.jpg` runs one capture end to end and
  prints the backend used. Run them over SSH while the game is running in
  Game Mode. They need the same environment as the service; over SSH
  `XDG_RUNTIME_DIR` is normally set (`/run/user/1000`).
- `screenshot.available: false` in Game Mode: check that
  `ls $XDG_RUNTIME_DIR/gamescope-0` exists and that `/tmp/.X11-unix/X0`
  exists. If you run an unusual setup, pin the socket in the unit with
  `Environment=GAMESCOPE_WAYLAND_DISPLAY=gamescope-0` and `systemctl --user daemon-reload`.
- Spectacle fails in Desktop Mode with "not authorized": KWin only allows
  screenshot clients it trusts; make sure the real `spectacle` binary runs (not
  a Flatpak wrapper), or use a custom command.
- Black or wrong image: try another `gamescopeType` (2 includes overlays), or
  force a backend with `"backends": ["gamescope"]`.
- Custom command examples: `"command": "spectacle -b -n -f -o {output}"`,
  `"command": "grim -o eDP-1 {output}"`, or any script that writes a PNG/JPEG to
  `{output}`. The file may also appear after the command exits (async tools).
- `journalctl --user -u vn-extractor -f` with `"logLevel": "debug"` logs every
  line and capture attempt.

## API

See [PROTOCOL.md](PROTOCOL.md). Summary: `GET /api/status`,
`GET /api/lines?after=&limit=`, `POST /api/lines`, `GET /api/ws[?after=]`
(WebSocket `hello` / `line` / `status` messages, pings every 20 s),
`POST /api/screenshot?lineId=&maxWidth=&quality=`, and `GET /` (debug page).

Implementation notes beyond the contract (all additive):

- `POST /api/lines` returns `200 {"duplicate": false, "filtered": true}` when a
  configured filter dropped the line.
- `GET /api/screenshot` works like `POST` (handy in a browser).
- A WebSocket client reconnecting with `?after=` that is more than 1000 lines
  behind gets the first 1000 in `hello` and the rest immediately afterwards as
  ordinary `line` messages, so it never has a gap.
- Errors are `{"error": "..."}` with 400 for bad parameters, 401 for auth,
  404 for unknown `/api/` paths, 503 when no screenshot could be taken.

### Quick test

```sh
curl -s http://steamdeck:7277/api/status | jq
curl -s -X POST --data-binary 'テストです。' -H 'Content-Type: text/plain' http://steamdeck:7277/api/lines
curl -s 'http://steamdeck:7277/api/lines?limit=5' | jq
websocat 'ws://steamdeck:7277/api/ws?after=0'          # or: curl --no-buffer 'ws://steamdeck:7277/api/ws'
curl -s -X POST -o shot.jpg -D - 'http://steamdeck:7277/api/screenshot?maxWidth=1280&quality=85'
# with a token:
curl -H 'Authorization: Bearer SECRET' http://steamdeck:7277/api/status
websocat 'ws://steamdeck:7277/api/ws?token=SECRET'
```

## Development

```sh
make            # vet, test, build ./vn-extractor
make race       # tests with the race detector
make release    # static linux/amd64 binary + tarball in dist/
VNX_TEST_DISPLAY=:0 go test ./internal/x11/   # optional X11 clipboard test against a real X server
```

Layout: `cmd/vn-extractor` (wiring, flags), `internal/config`,
`internal/store` (JSONL line log, filters), `internal/sources` (WebSocket and
clipboard inputs), `internal/screenshot` (backend chain, JPEG encoding,
per-line cache), `internal/gamescope` (minimal `gamescope_control` Wayland
client), `internal/x11` (pure-Go X11 helpers: gamescope request, clipboard),
`internal/desktop` (session discovery from a systemd user service),
`internal/server` (HTTP/WebSocket API and debug page), `internal/hub`,
`internal/status`.

A real gamescope can be tested without a Deck:
`gamescope --backend headless -W 1280 -H 800 -- vkcube` and then
`vn-extractor -detect` / `-screenshot-test` in another terminal.
