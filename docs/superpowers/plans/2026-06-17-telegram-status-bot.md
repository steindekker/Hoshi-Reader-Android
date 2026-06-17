# Telegram Status Bot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `--notify` flag that sends each run's result to a personal Telegram chat, plus a thin `anki-enrich-bot` listener that triggers runs on `/run`.

**Architecture:** The `anki-enrich` CLI owns "run + report" behind `--notify`; both the (later) nightly schedule and the bot's `/run` go through that same invocation, so there is one report path. `notify.py` is stdlib-only (`urllib`) outbound transport + formatting. `bot.py` uses `pyTelegramBotAPI` (`telebot`) for polling/commands/buttons and shells out to the CLI. A non-blocking `flock` makes overlapping runs impossible.

**Tech Stack:** Python ≥3.11, `uv`, stdlib `urllib`/`fcntl`, `pyTelegramBotAPI`, `pytest`.

---

## File structure

- Create `ankienrich/runlock.py` — non-blocking collection run-lock (flock context manager).
- Create `ankienrich/notify.py` — Telegram transport (`send_message`) + report formatting (`format_report`). Stdlib only.
- Create `ankienrich/bot.py` — `anki-enrich-bot` listener (telebot). Thin: shells out to the CLI.
- Modify `ankienrich/paths.py` — add `RUN_LOCK`.
- Modify `ankienrich/pipeline.py` — run steps as a tagged list; raise `PipelineError(step, original)` on failure.
- Modify `ankienrich/cli.py` — `--notify` flag, extract `_run(args)`, wrap apply runs in the lock, send report on success/failure.
- Modify `pyproject.toml` — `anki-enrich-bot` script + `bot` dependency group (default-included).
- Tests: `tests/test_runlock.py`, `tests/test_notify.py`, `tests/test_pipeline_errors.py`, `tests/test_bot.py`, additions to `tests/test_cli.py`.

The `res` dict shape (already returned by `pipeline.run`) — relevant for `format_report`:
- `res["scanned"]` (int), `res["backup"]` (str|None), `res["steps"]` with keys `audio`, `pitch`, `sentences`, `enrich`, `examples`, `cloze`.
- Each step is either `{"skipped": "<reason>"}` or, on **dry-run**: `audio{will_fill,will_upgrade,none}`, `pitch{filled,not_found,skipped}`, `sentences{filled}`, `enrich{to_write,nohit}`, `examples{to_write,none}`, `cloze{filled,skipped}`; on **apply**: `audio{filled:dict,upgraded:dict}`, `enrich{written,dropped}`, `examples{written,none_tagged}` (pitch/sentences/cloze unchanged).

---

## Task 1: Run-lock module

**Files:**
- Create: `ankienrich/runlock.py`
- Modify: `ankienrich/paths.py` (add `RUN_LOCK`)
- Test: `tests/test_runlock.py`

- [ ] **Step 1: Add the lock path**

In `ankienrich/paths.py`, after the `WORKING_COLLECTION` line add:

```python
RUN_LOCK = os.path.join(VAR_DIR, "run.lock")
```

- [ ] **Step 2: Write the failing test**

Create `tests/test_runlock.py`:

```python
import pytest

from ankienrich import runlock


def test_second_acquire_is_busy():
    with runlock.acquire():
        with pytest.raises(runlock.Busy):
            with runlock.acquire():
                pass


def test_lock_is_reusable_after_release():
    with runlock.acquire():
        pass
    with runlock.acquire():  # must not raise — first was released
        pass
```

- [ ] **Step 3: Run it (fails — module missing)**

Run: `uv run pytest tests/test_runlock.py -v`
Expected: FAIL, `ModuleNotFoundError: ankienrich.runlock`.

- [ ] **Step 4: Implement the module**

Create `ankienrich/runlock.py`:

```python
"""Non-blocking run-lock so a nightly run and a manual /run can never write the
single collection at once — whoever is second gets Busy and bows out."""
from __future__ import annotations

import contextlib
import fcntl

from . import paths


class Busy(Exception):
    """Another run already holds the lock."""


@contextlib.contextmanager
def acquire():
    paths.ensure_var()
    f = open(paths.RUN_LOCK, "w")
    try:
        fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        f.close()
        raise Busy()
    try:
        yield
    finally:
        fcntl.flock(f, fcntl.LOCK_UN)
        f.close()
```

- [ ] **Step 5: Run tests (pass)**

Run: `uv run pytest tests/test_runlock.py -v`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add ankienrich/runlock.py ankienrich/paths.py tests/test_runlock.py
git commit -m "feat: non-blocking run-lock to serialise collection writes"
```

---

## Task 2: Telegram transport (`notify.send_message`)

**Files:**
- Create: `ankienrich/notify.py`
- Test: `tests/test_notify.py`

- [ ] **Step 1: Write the failing test**

Create `tests/test_notify.py`:

```python
import json

import pytest

from ankienrich import notify


def test_send_message_posts_expected_payload(monkeypatch):
    sent = {}

    class FakeResp:
        def close(self): pass

    def fake_urlopen(req, timeout=None):
        sent["url"] = req.full_url
        sent["body"] = json.loads(req.data.decode())
        return FakeResp()

    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "TOK")
    monkeypatch.setenv("TELEGRAM_CHAT_ID", "42")
    monkeypatch.setattr(notify.urllib.request, "urlopen", fake_urlopen)

    notify.send_message("hello")

    assert sent["url"] == "https://api.telegram.org/botTOK/sendMessage"
    assert sent["body"] == {"chat_id": "42", "text": "hello", "parse_mode": "HTML"}


def test_send_message_requires_env(monkeypatch):
    monkeypatch.delenv("TELEGRAM_BOT_TOKEN", raising=False)
    monkeypatch.delenv("TELEGRAM_CHAT_ID", raising=False)
    with pytest.raises(RuntimeError):
        notify.send_message("hello")
```

- [ ] **Step 2: Run it (fails — module missing)**

Run: `uv run pytest tests/test_notify.py -v`
Expected: FAIL, `ModuleNotFoundError: ankienrich.notify`.

- [ ] **Step 3: Implement transport**

Create `ankienrich/notify.py`:

```python
"""Telegram transport + run-report formatting. Stdlib only (urllib): the report
is a single POST, so the nightly path carries no third-party dependency."""
from __future__ import annotations

import html
import json
import os
import urllib.request

_API = "https://api.telegram.org"


def _credentials() -> tuple[str, str]:
    token = os.environ.get("TELEGRAM_BOT_TOKEN")
    chat = os.environ.get("TELEGRAM_CHAT_ID")
    if not token or not chat:
        raise RuntimeError(
            "set TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID in the environment")
    return token, chat


def send_message(text: str) -> None:
    token, chat = _credentials()
    payload = {"chat_id": chat, "text": text, "parse_mode": "HTML"}
    req = urllib.request.Request(
        f"{_API}/bot{token}/sendMessage",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"})
    urllib.request.urlopen(req, timeout=30).close()
```

- [ ] **Step 4: Run tests (pass)**

Run: `uv run pytest tests/test_notify.py -v`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add ankienrich/notify.py tests/test_notify.py
git commit -m "feat: stdlib Telegram sendMessage transport"
```

---

## Task 3: Report formatting (`notify.format_report`)

**Files:**
- Modify: `ankienrich/notify.py`
- Test: `tests/test_notify.py`

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_notify.py`:

```python
def _dry_res():
    return {"scanned": 1234, "backup": None, "steps": {
        "audio": {"will_fill": 12, "will_upgrade": 3, "none": 4},
        "pitch": {"filled": 8, "not_found": 2, "skipped": 1},
        "sentences": {"filled": 5},
        "enrich": {"to_write": 10, "nohit": 1},
        "examples": {"to_write": 7, "none": 2},
        "cloze": {"filled": 4, "skipped": 1}}}


def _apply_res():
    return {"scanned": 1234, "backup": "var/backup.colpkg", "steps": {
        "audio": {"filled": {"nhk": 9, "forvo": 3}, "upgraded": {"nhk": 3}},
        "pitch": {"filled": 8, "not_found": 2, "skipped": 1},
        "sentences": {"filled": 5},
        "enrich": {"written": 10, "dropped": 0},
        "examples": {"written": 7, "none_tagged": 2},
        "cloze": {"filled": 4, "skipped": 1}}}


def test_format_report_dry_run():
    out = notify.format_report(_dry_res(), apply=False, pull_action="none")
    assert "dry run" in out
    assert "1234" in out and "scanned" in out
    assert "sentence" in out and "5" in out


def test_format_report_apply_has_verdict_and_push():
    out = notify.format_report(
        _apply_res(), apply=True, pull_action="normal", push_action="normal")
    assert "applied" in out
    assert "12" in out  # 9 + 3 audio filled
    assert "push" in out


def test_format_report_skipped_step_renders_dash():
    res = _dry_res()
    res["steps"]["audio"] = {"skipped": "entries.db not found"}
    out = notify.format_report(res, apply=False, pull_action="none")
    assert "entries.db not found" in out


def test_format_report_failure_names_pass_and_escapes():
    out = notify.format_report(
        None, apply=True, pull_action=None,
        error=("defs", RuntimeError("missing <db>")))
    assert "FAILED at defs" in out
    assert "RuntimeError: missing &lt;db&gt;" in out  # HTML-escaped
```

- [ ] **Step 2: Run them (fail — no format_report)**

Run: `uv run pytest tests/test_notify.py -k format_report -v`
Expected: FAIL, `AttributeError: ... has no attribute 'format_report'`.

- [ ] **Step 3: Implement formatting**

Append to `ankienrich/notify.py`:

```python
# AnkiWeb sync action code -> human text (mirrors the CLI's sync wording).
_SYNC_TEXT = {
    "pull": {"none": "already up to date", "normal": "fetched changes",
             "download": "downloaded full collection"},
    "push": {"none": "nothing to upload", "normal": "uploaded changes",
             "upload": "uploaded full collection"},
}


def _row(label: str, value, detail: str = "") -> str:
    line = f"{label:<9}{str(value):>5}"
    return f"{line}   {detail}" if detail else line


def format_report(res, *, apply, pull_action, push_action=None, error=None) -> str:
    """Telegram HTML report for one run. `error` is (pass_name, exception)."""
    if error is not None:
        step, exc = error
        body = f"{type(exc).__name__}: {exc}" if isinstance(exc, BaseException) else str(exc)
        return (f"<b>anki-enrich — ❌ FAILED at {html.escape(step)}</b>\n"
                f"<pre>{html.escape(body)}</pre>")

    s = res["steps"]
    rows = [_row("scanned", res["scanned"], "cards")]
    rows.append(_row("sync", "", f"pull — {_SYNC_TEXT['pull'].get(pull_action, pull_action)}"))
    if apply and push_action is not None:
        rows.append(_row("sync", "", f"push — {_SYNC_TEXT['push'].get(push_action, push_action)}"))

    a = s["audio"]
    if "skipped" in a:
        rows.append(_row("audio", "—", f"skipped: {a['skipped']}"))
    elif apply:
        filled, upgraded = sum(a["filled"].values()), sum(a["upgraded"].values())
        rows.append(_row("audio", filled, f"{upgraded} upgraded"))
    else:
        rows.append(_row("audio", a["will_fill"], f"{a['will_upgrade']} to upgrade · {a['none']} no match"))

    p = s["pitch"]
    rows.append(_row("pitch", p["filled"], f"{p['not_found']} not in dict · {p['skipped']} no reading"))
    rows.append(_row("sentence", s["sentences"]["filled"], "JRP markup"))

    e = s["enrich"]
    if "skipped" in e:
        rows.append(_row("defs", "—", f"skipped: {e['skipped']}"))
    elif apply:
        rows.append(_row("defs", e["written"], f"{e['dropped']} dropped"))
    else:
        rows.append(_row("defs", e["to_write"], f"{e['nohit']} no-hit"))

    x = s["examples"]
    if "skipped" in x:
        rows.append(_row("examples", "—", f"skipped: {x['skipped']}"))
    elif apply:
        rows.append(_row("examples", x["written"], f"{x['none_tagged']} no-example"))
    else:
        rows.append(_row("examples", x["to_write"], f"{x['none']} no-example"))

    c = s["cloze"]
    rows.append(_row("cloze", c["filled"], f"{c['skipped']} unlocatable"))

    verdict = "✓ applied + pushed" if apply else "dry run — pass --apply to write"
    return f"<b>anki-enrich — {verdict}</b>\n<pre>{html.escape(chr(10).join(rows))}</pre>"
```

- [ ] **Step 4: Run tests (pass)**

Run: `uv run pytest tests/test_notify.py -v`
Expected: PASS (all).

- [ ] **Step 5: Commit**

```bash
git add ankienrich/notify.py tests/test_notify.py
git commit -m "feat: format run result as a Telegram report"
```

---

## Task 4: Pipeline failure tagging

**Files:**
- Modify: `ankienrich/pipeline.py`
- Test: `tests/test_pipeline_errors.py`

- [ ] **Step 1: Write the failing test**

Create `tests/test_pipeline_errors.py`:

```python
import pytest

from ankienrich import pipeline


def test_step_failure_is_tagged_with_pass_name(monkeypatch):
    monkeypatch.setattr(pipeline.def_build, "ensure", lambda: "no-source")

    class FakeCol:
        def find_notes(self, q): return []

    def boom(col, apply):
        raise RuntimeError("kaboom")

    monkeypatch.setattr(pipeline, "_step_audio", boom)

    with pytest.raises(pipeline.PipelineError) as ei:
        pipeline.run(FakeCol(), apply=False)
    assert ei.value.step == "audio"
    assert isinstance(ei.value.original, RuntimeError)
```

- [ ] **Step 2: Run it (fails — no PipelineError)**

Run: `uv run pytest tests/test_pipeline_errors.py -v`
Expected: FAIL, `AttributeError: ... has no attribute 'PipelineError'`.

- [ ] **Step 3: Implement tagging**

In `ankienrich/pipeline.py`, add near the top (after the imports):

```python
class PipelineError(Exception):
    """A named enrichment pass raised. Carries the pass name for reporting."""

    def __init__(self, step: str, original: BaseException):
        super().__init__(f"{step}: {original}")
        self.step = step
        self.original = original
```

Then replace the body of `run()` from the first `out["steps"]["audio"] = ...` line through `return out` with:

```python
    steps = [
        ("audio", "audio", lambda: _step_audio(col, apply)),
        ("pitch", "pitch", lambda: (
            pitch.backfill_pitch(col, overwrite=overwrite_pitch) if apply
            else pitch.build_pitch_plan(col, overwrite=overwrite_pitch)["counts"])),
        ("sentences", "sentence", lambda: _step_sentences(col, apply, overwrite_sentences)),
        ("enrich", "defs", lambda: _step_enrich(col, apply, def_index, overwrite_defs)),
        ("examples", "examples", lambda: _step_examples(col, apply, overwrite_examples)),
        ("cloze", "cloze", lambda: cloze.run(col, apply=apply, overwrite=overwrite_cloze)),
    ]
    for key, name, fn in steps:
        try:
            out["steps"][key] = fn()
        except Exception as exc:
            raise PipelineError(name, exc) from exc
    return out
```

(Storage keys stay identical to before — `cli._render` is unaffected; only the error name differs, e.g. `sentence`/`defs`.)

- [ ] **Step 4: Run the test + the full suite (pass)**

Run: `uv run pytest tests/test_pipeline_errors.py tests/test_cli.py -v`
Expected: PASS (existing CLI tests still green — `run()`'s output shape is unchanged).

- [ ] **Step 5: Commit**

```bash
git add ankienrich/pipeline.py tests/test_pipeline_errors.py
git commit -m "feat: tag pipeline step failures with the pass name"
```

---

## Task 5: CLI `--notify` + lock wrap

**Files:**
- Modify: `ankienrich/cli.py`
- Test: `tests/test_cli.py`

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_cli.py`:

```python
def _ok_res():
    return {"scanned": 0, "backup": None, "steps": {
        "audio": {"skipped": "x"}, "pitch": {"filled": 0, "not_found": 0, "skipped": 0},
        "sentences": {"filled": 0}, "enrich": {"skipped": "x"},
        "examples": {"skipped": "x"}, "cloze": {"filled": 0, "skipped": 0}}}


def _wire_run(monkeypatch, run_fn, *, push=("none", None)):
    class FakeCol:
        def close(self): pass
    monkeypatch.setattr(cli.collection, "open_working", lambda: FakeCol())
    monkeypatch.setattr(cli.sync, "login", lambda col: None)
    monkeypatch.setattr(cli.sync, "pull", lambda col, auth: ("none", auth))
    monkeypatch.setattr(cli.sync, "push", lambda col, auth: push)
    monkeypatch.setattr(cli.collection, "backup", lambda col: None)
    monkeypatch.setattr(cli.pipeline, "run", run_fn)


def test_notify_sends_report_on_success(monkeypatch):
    sent = []
    monkeypatch.setattr(cli.notify, "send_message", lambda text: sent.append(text))
    _wire_run(monkeypatch, lambda col, **kw: _ok_res())
    assert cli.main(["--notify"]) == 0
    assert sent and "anki-enrich" in sent[0]


def test_notify_sends_failure_and_skips_push(monkeypatch):
    sent, pushed = [], []
    monkeypatch.setattr(cli.notify, "send_message", lambda text: sent.append(text))

    def boom(col, **kw):
        raise cli.pipeline.PipelineError("defs", RuntimeError("nope"))

    _wire_run(monkeypatch, boom)
    monkeypatch.setattr(cli.sync, "push", lambda col, auth: pushed.append(True) or ("none", auth))
    with pytest.raises(cli.pipeline.PipelineError):
        cli.main(["--apply", "--notify"])
    assert sent and "FAILED at defs" in sent[0]
    assert pushed == [], "must not push after a failed run"


def test_notify_reports_lock_busy_without_running(monkeypatch):
    sent, ran = [], []
    monkeypatch.setattr(cli.notify, "send_message", lambda text: sent.append(text))
    _wire_run(monkeypatch, lambda col, **kw: ran.append(True) or _ok_res())

    import contextlib
    @contextlib.contextmanager
    def busy():
        raise cli.runlock.Busy()
        yield
    monkeypatch.setattr(cli.runlock, "acquire", busy)

    assert cli.main(["--apply", "--notify"]) == 0
    assert ran == [], "must not run while locked"
    assert sent and "already in progress" in sent[0]
```

Add `import pytest` at the top of `tests/test_cli.py` if not present.

- [ ] **Step 2: Run them (fail)**

Run: `uv run pytest tests/test_cli.py -k "notify or lock" -v`
Expected: FAIL (`--notify` unknown / `cli.notify` missing).

- [ ] **Step 3: Wire imports + flag**

In `ankienrich/cli.py`, update the import line:

```python
from . import collection, dedupe as _dedupe, notify, pipeline, runlock, sync, template, wizard
```

Add the flag in the argparse block (next to `--dry-run`):

```python
        ap.add_argument("--notify", action="store_true",
                        help="send the run result to Telegram (needs TELEGRAM_* env)")
```

And ensure the wizard path sets it: in `main`, right after `args = wizard.run(...)` returns non-None, add:

```python
        args.notify = "--notify" in argv
```

- [ ] **Step 4: Extract `_run` and wrap with the lock**

Replace the body of `main` from `col = collection.open_working()` through the closing `return 0` with a call into an extracted `_run`, and add the lock wrapper. The new tail of `main`:

```python
    if getattr(args, "apply", False):
        try:
            with runlock.acquire():
                return _run(args)
        except runlock.Busy:
            msg = "⏳ a run is already in progress, skipping"
            if getattr(args, "notify", False):
                notify.send_message(msg)
            else:
                print("\n  " + _dim(msg) + "\n")
            return 0
    return _run(args)
```

Then add `_run` above `main`:

```python
def _run(args) -> int:
    col = collection.open_working()
    notify_on = getattr(args, "notify", False)
    phase, pull_action = "sync/login", None
    try:
        auth = sync.login(col)
        phase = "sync/pull"
        pull_action, auth = sync.pull(col, auth)
        _sync_line("pull", pull_action)
        if args.command == "dedupe":
            if args.apply:
                collection.backup(col)
            res = _dedupe.run(col, apply=args.apply)
            if args.apply:
                push_action, auth = sync.push(col, auth)
                _sync_line("push", push_action)
            _render_dedupe(res, args.apply)
        elif args.command == "template":
            if args.apply:
                collection.backup(col)
                template.apply(col)
                push_action, auth = sync.push(col, auth)
                _sync_line("push", push_action)
                print("\n  " + _green("✓ card design applied + pushed.") + "\n")
            else:
                print("\n  " + _dim("dry run — would apply repo card design "
                                     "(front/back/style.css). pass --apply to write.") + "\n")
        else:
            res = pipeline.run(
                col, apply=args.apply,
                overwrite_pitch=args.overwrite_pitch or args.overwrite,
                overwrite_sentences=args.overwrite_sentences or args.overwrite,
                overwrite_defs=args.overwrite_defs or args.overwrite,
                overwrite_examples=args.overwrite_examples or args.overwrite,
                overwrite_cloze=args.overwrite_cloze or args.overwrite)
            push_action = None
            if args.apply:
                phase = "sync/push"
                push_action, auth = sync.push(col, auth)
                _sync_line("push", push_action)
            _render(res, args.apply)
            if notify_on:
                # _safe_notify: a send failure must not flip the exit status of
                # an already-completed apply.
                _safe_notify(notify.format_report(
                    res, apply=args.apply, pull_action=pull_action,
                    push_action=push_action))
    except pipeline.PipelineError as exc:
        if notify_on:
            _safe_notify(notify.format_report(
                None, apply=args.apply, pull_action=pull_action,
                error=(exc.step, exc.original)))
        raise
    except Exception as exc:
        if notify_on:
            _safe_notify(notify.format_report(
                None, apply=getattr(args, "apply", False), pull_action=pull_action,
                error=(phase, exc)))
        raise
    finally:
        col.close()
    return 0
```

Add the small helper above `_run` (a send failure must not mask the real error):

```python
def _safe_notify(text: str) -> None:
    try:
        notify.send_message(text)
    except Exception as exc:  # noqa: BLE001 — never mask the underlying failure
        print(_red(f"telegram notify failed: {exc}"), file=sys.stderr)
```

(`--notify` is only honoured for the `run` command; `dedupe`/`template` ignore it, which is fine — the bot only ever calls `run`.)

- [ ] **Step 5: Run tests (pass)**

Run: `uv run pytest tests/test_cli.py -v`
Expected: PASS (existing + 3 new).

- [ ] **Step 6: Commit**

```bash
git add ankienrich/cli.py tests/test_cli.py
git commit -m "feat: --notify sends run result to Telegram; lock guards applies"
```

---

## Task 6: The bot listener

**Files:**
- Create: `ankienrich/bot.py`
- Modify: `pyproject.toml` (script + `bot` group)
- Test: `tests/test_bot.py`

- [ ] **Step 1: Add the dependency + entry point**

In `pyproject.toml`, under `[project.scripts]` add:

```toml
anki-enrich-bot = "ankienrich.bot:main"
```

Under `[dependency-groups]` add:

```toml
bot = ["pyTelegramBotAPI>=4"]
```

And include it in defaults:

```toml
[tool.uv]
default-groups = ["dev", "jrp", "wizard", "bot"]
```

Then sync: `uv sync` (installs `telebot`).

- [ ] **Step 2: Write the failing tests**

Create `tests/test_bot.py`:

```python
from ankienrich import bot


def test_spawn_argv_apply():
    assert bot._spawn_argv(overwrite=False) == [
        "anki-enrich", "--apply", "--notify"]


def test_spawn_argv_overwrite():
    assert bot._spawn_argv(overwrite=True) == [
        "anki-enrich", "--apply", "--overwrite", "--notify"]


def test_authorized():
    assert bot._authorized(42, 42)
    assert not bot._authorized(99, 42)


def test_run_keyboard_has_three_actions():
    kb = bot._run_keyboard()
    datas = [b.callback_data for row in kb.keyboard for b in row]
    assert datas == ["run:apply", "run:overwrite", "run:cancel"]


def test_dispatch_spawns_on_apply(monkeypatch):
    spawned = {}
    monkeypatch.setattr(bot.subprocess, "Popen",
                        lambda argv, **kw: spawned.setdefault("argv", argv))
    bot._dispatch("run:overwrite")
    assert spawned["argv"] == ["anki-enrich", "--apply", "--overwrite", "--notify"]


def test_dispatch_cancel_does_not_spawn(monkeypatch):
    spawned = []
    monkeypatch.setattr(bot.subprocess, "Popen",
                        lambda argv, **kw: spawned.append(argv))
    assert bot._dispatch("run:cancel") is None
    assert spawned == []
```

- [ ] **Step 3: Run them (fail — module missing)**

Run: `uv run pytest tests/test_bot.py -v`
Expected: FAIL, `ModuleNotFoundError: ankienrich.bot`.

- [ ] **Step 4: Implement the bot**

Create `ankienrich/bot.py`:

```python
"""Telegram command listener. Thin: /run shells out to
`anki-enrich --apply [--overwrite] --notify`, which sends the report itself —
the bot never blocks on a run and never re-implements the pipeline."""
from __future__ import annotations

import os
import subprocess

import telebot


def _spawn_argv(*, overwrite: bool) -> list[str]:
    argv = ["anki-enrich", "--apply", "--notify"]
    if overwrite:
        argv.insert(2, "--overwrite")
    return argv


def _authorized(chat_id: int, expected: int) -> bool:
    return chat_id == expected


def _run_keyboard() -> telebot.types.InlineKeyboardMarkup:
    kb = telebot.types.InlineKeyboardMarkup()
    kb.add(telebot.types.InlineKeyboardButton("Apply", callback_data="run:apply"),
           telebot.types.InlineKeyboardButton("Apply + overwrite", callback_data="run:overwrite"))
    kb.add(telebot.types.InlineKeyboardButton("Cancel", callback_data="run:cancel"))
    return kb


def _dispatch(data: str) -> str | None:
    """Act on a callback. Returns the confirmation label, or None for cancel."""
    action = data.split(":", 1)[1]
    if action == "cancel":
        return None
    subprocess.Popen(_spawn_argv(overwrite=action == "overwrite"),
                     start_new_session=True,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return ("▶️ Run started (apply + overwrite)" if action == "overwrite"
            else "▶️ Run started")


def _config() -> tuple[str, int]:
    token = os.environ.get("TELEGRAM_BOT_TOKEN")
    chat = os.environ.get("TELEGRAM_CHAT_ID")
    if not token or not chat:
        raise RuntimeError(
            "set TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID in the environment")
    return token, int(chat)


def build_bot(token: str, chat_id: int) -> telebot.TeleBot:
    bot = telebot.TeleBot(token, parse_mode="HTML")

    @bot.message_handler(commands=["start", "help"])
    def _help(m):
        if not _authorized(m.chat.id, chat_id):
            return
        bot.reply_to(m, "Commands:\n/run — run enrichment now")

    @bot.message_handler(commands=["run"])
    def _run(m):
        if not _authorized(m.chat.id, chat_id):
            return
        bot.reply_to(m, "Run enrichment now?", reply_markup=_run_keyboard())

    @bot.callback_query_handler(func=lambda c: True)
    def _cb(c):
        if not _authorized(c.message.chat.id, chat_id):
            return
        label = _dispatch(c.data)
        bot.answer_callback_query(c.id, "Cancelled" if label is None else "Started")
        bot.edit_message_text(label or "🚫 Cancelled",
                              c.message.chat.id, c.message.message_id)

    return bot


def main(argv=None) -> int:
    token, chat_id = _config()
    build_bot(token, chat_id).infinity_polling()
    return 0
```

- [ ] **Step 5: Run tests (pass)**

Run: `uv run pytest tests/test_bot.py -v`
Expected: PASS (6 tests).

- [ ] **Step 6: Full suite + commit**

Run: `uv run pytest -q`
Expected: all green.

```bash
git add ankienrich/bot.py pyproject.toml uv.lock tests/test_bot.py
git commit -m "feat: anki-enrich-bot — /run triggers an enrichment run"
```

---

## Manual smoke test (optional, needs a real bot token)

1. Create a bot via @BotFather, grab the token; get your chat id (message the bot, then `getUpdates`).
2. `export TELEGRAM_BOT_TOKEN=... TELEGRAM_CHAT_ID=...`
3. Report path: `uv run anki-enrich --notify` (dry-run) → a summary lands in your chat.
4. Bot path: `uv run anki-enrich-bot` in one shell; message `/run` → tap **Apply** → a run starts and its report arrives.
