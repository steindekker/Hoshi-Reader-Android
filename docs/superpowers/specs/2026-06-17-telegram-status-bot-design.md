# Telegram status bot — design

/ 2026-06-17

## Goal

A personal Telegram bot that (1) reports the result of every enrichment run to my
chat, and (2) lets me trigger a run on demand with `/run`. This phase builds the
**application pieces** only; installing it on Hetzner (systemd timer + service,
secrets) is a separate later session and is intentionally out of scope here.

## Decisions

- **Interaction:** interactive bot — nightly auto-report + `/run` trigger. (No
  `/status`, `/logs` for now.)
- **Architecture A:** the `anki-enrich` CLI owns "run + report" behind a
  `--notify` flag. Both the nightly schedule (later) and the bot's `/run` go
  through that same invocation, so there is exactly one report path.
- **`/run`** applies by default but asks about overwrites first: it replies with
  inline buttons `[Apply] [Apply + overwrite] [Cancel]`.
- **Failure report** names the failing pass plus the exception message
  (`❌ FAILED at <pass>: <msg>`), not a full traceback.
- **Libraries:** `notify.py` uses stdlib `urllib` (the report is one POST — no
  dependency on the critical nightly path). `bot.py` uses `pyTelegramBotAPI`
  (`telebot`) for polling/commands/callbacks — readable, synchronous, light.

## Components

### 1. `ankienrich/notify.py` — transport + formatting

Pure outbound, stdlib only.

- `send_message(text, *, buttons=None) -> None` — POSTs to the Bot API
  `sendMessage` via `urllib.request`. Reads `TELEGRAM_BOT_TOKEN` and
  `TELEGRAM_CHAT_ID` from env; raises a clear error if either is unset. Uses
  `parse_mode=HTML`; `buttons`, when given, is serialised as an
  `inline_keyboard` reply markup.
- `format_report(res, *, apply, pull_action, push_action, error=None) -> str` —
  builds the message. Mirrors the rows the CLI's `_render` prints (scanned, sync
  pull/push, audio/pitch/sentence/defs/examples/cloze counts, and the
  applied/dry-run verdict) inside a monospace `<pre>` block so column alignment
  survives in Telegram. When `error` is set, returns the failure form
  `❌ FAILED at <pass>: <message>` instead of the table.

`format_report` is the single source of truth for what a run summary looks like;
it takes the same `res` dict that `pipeline.run()` already returns.

### 2. CLI `--notify` flag (`ankienrich/cli.py`)

- New `--notify` argument on the default `run` command.
- The run sequence (login → pull → `pipeline.run` → push) is wrapped so that:
  - On success: `notify.format_report(...)` is sent.
  - On any exception: the failure report is sent (tagged with the failing
    phase/pass) and the process exits non-zero **without pushing** — the backup
    is already taken, so a partially-written apply is recoverable.
- A Telegram send failure *after* a successful apply is logged to stderr but does
  not undo completed work or change the exit status of the enrichment itself.

**Naming the failing pass.** `pipeline.run()` runs its steps as an ordered list of
`(name, fn)` and, on a step exception, re-raises a small `PipelineError(step=...)`
(from the original). The CLI catches `PipelineError` for in-pipeline failures and
tags pre-pipeline failures itself (`sync/login`, `sync/pull`).

### 3. `ankienrich/bot.py` — the listener (`anki-enrich-bot` entry point)

- Uses `telebot` long-polling (`infinity_polling`) — no webhook, no inbound
  ports.
- **Allowlist:** every handler first checks `message.chat.id == TELEGRAM_CHAT_ID`;
  anything else is ignored. The bot never acts for a chat that isn't mine.
- **`/run`** → replies with an inline keyboard `[Apply] [Apply + overwrite]
  [Cancel]`.
- **Button callback** → spawns `anki-enrich --apply [--overwrite] --notify`
  **detached** (`subprocess.Popen`) and immediately acks `▶️ Run started` /
  `🚫 Cancelled`. The poll loop never blocks for the minutes a run takes; the
  spawned CLI's own `--notify` delivers the result when it finishes.
- **`/help`** (and `/start`) → one-line usage.

### 4. Collision lock (`ankienrich/paths.py` + the run path)

- New `RUN_LOCK = var/run.lock`.
- The `anki-enrich` run path acquires a non-blocking `flock` (`LOCK_EX | LOCK_NB`)
  before doing any work. If the nightly job and a manual `/run` overlap, the
  second one to start sends `⏳ a run is already in progress, skipping` (when
  `--notify`) and exits cleanly. Collisions are impossible by construction.

### 5. Packaging

- Add `anki-enrich-bot = "ankienrich.bot:main"` to `[project.scripts]`.
- Add a `bot` dependency group with `pyTelegramBotAPI`, included in the default
  groups (mirrors how `wizard`/`jrp` are set up).

## Config / secrets

- New env vars: `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`.
- `--notify` and the bot both error loudly at startup if these are unset.

## Out of scope (next session)

- systemd `anki-enrich-nightly.service` + `.timer`, `anki-enrich-bot.service`,
  `EnvironmentFile`, and the Hetzner deploy. Built and run later.

## Testing

- **`format_report`** — success-shaped `res` → expected rows; skipped steps render
  as skipped; failure form → `FAILED at <pass>`.
- **`send_message`** — monkeypatch `urllib` to capture the request; assert the URL,
  `chat_id`, `parse_mode`, and serialised `inline_keyboard`. Assert it raises when
  env vars are missing.
- **`bot`** — fake update/callback objects → correct buttons built, foreign
  `chat_id` ignored, the right `anki-enrich` argv is spawned (mock `Popen`).
- **`PipelineError` tagging** — force a step to throw, assert the captured step
  name flows into the failure report.
- **flock** — a second non-blocking acquisition fails fast.
