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
