"""Telegram transport + run-report formatting."""
from __future__ import annotations

import html
import os

import requests

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
    try:
        resp = requests.post(
            f"{_API}/bot{token}/sendMessage", json=payload, timeout=30)
        resp.raise_for_status()
    except requests.HTTPError as e:
        # Telegram returns a 4xx with a JSON body explaining why (bad token,
        # "chat not found", …); surface that instead of an opaque HTTP status.
        try:
            desc = e.response.json().get("description") or str(e)
        except Exception:
            desc = str(e)
        raise RuntimeError(f"Telegram API error: {desc}") from e


# AnkiWeb sync action code -> human text (mirrors the CLI's sync wording).
_SYNC_TEXT = {
    "pull": {"none": "already up to date", "normal": "fetched changes",
             "download": "downloaded full collection"},
    "push": {"none": "nothing to upload", "normal": "uploaded changes",
             "upload": "uploaded full collection"},
}


# Per-pass glyphs. One emoji a line — enough to scan on a phone, not a parade.
_EMOJI = {"audio": "🔊", "pitch": "📈", "sentence": "📝", "furigana": "🔤",
          "defs": "📖", "examples": "💬", "cloze": "🧩"}

# Newly-defined terms listed inline; cap so a first bulk run can't flood the chat.
_TERM_CAP = 20


def _detail(*items) -> str:
    """Join "<n> <noun>" fragments, dropping zero counts so lines stay quiet."""
    return " · ".join(f"{n} {noun}" for n, noun in items if n)


def _line(label: str, n, detail: str = "") -> str:
    tail = f"<i> · {detail}</i>" if detail else ""
    return f"{_EMOJI[label]} <b>{n}</b> {label}{tail}"


def _skipped(label: str, reason: str) -> str:
    return f"{_EMOJI[label]} <i>{label} skipped · {html.escape(reason)}</i>"


def format_report(res, *, apply, pull_action, push_action=None, error=None) -> str:
    """Telegram HTML report for one run. `error` is (pass_name, exception)."""
    if error is not None:
        step, exc = error
        body = f"{type(exc).__name__}: {exc}" if isinstance(exc, BaseException) else str(exc)
        return (f"❌ <b>anki-enrich</b> · failed at {html.escape(step)}\n"
                f"<pre>{html.escape(body)}</pre>")

    s = res["steps"]
    verdict = "applied + pushed" if apply else "dry run"
    lines = [f"<b>anki-enrich</b> · {verdict}",
             f"<i>{res['scanned']:,} cards scanned</i>", ""]

    a = s["audio"]
    if "skipped" in a:
        lines.append(_skipped("audio", a["skipped"]))
    elif apply:
        filled = sum(a["filled"].values())
        lines.append(_line("audio", filled, _detail((a["errors"], "errors"))))
    else:
        lines.append(_line("audio", a["will_fill"],
                           _detail((a["none"], "no match"))))

    p = s["pitch"]
    lines.append(_line("pitch", p["filled"],
                       _detail((p["not_found"], "not in dict"), (p["skipped"], "no reading"))))
    lines.append(_line("sentence", s["sentences"]["filled"]))
    lines.append(_line("furigana", s["furigana"]["fixed"]))

    e = s["enrich"]
    if "skipped" in e:
        lines.append(_skipped("defs", e["skipped"]))
    elif apply:
        lines.append(_line("defs", e["written"], _detail((e["dropped"], "dropped"))))
    else:
        lines.append(_line("defs", e["to_write"], _detail((e["nohit"], "no-hit"))))

    x = s["examples"]
    if "skipped" in x:
        lines.append(_skipped("examples", x["skipped"]))
    elif apply:
        lines.append(_line("examples", x["written"], _detail((x["none_tagged"], "none"))))
    else:
        lines.append(_line("examples", x["to_write"], _detail((x["none"], "none"))))

    c = s["cloze"]
    lines.append(_line("cloze", c["filled"], _detail((c["skipped"], "unlocatable"))))

    terms = s.get("enrich", {}).get("terms") or []
    if terms:
        shown = "、".join(html.escape(t) for t in terms[:_TERM_CAP])
        if len(terms) > _TERM_CAP:
            shown += f" ＋{len(terms) - _TERM_CAP}"
        lines += ["", f"🆕 <i>{shown}</i>"]

    pull = _SYNC_TEXT["pull"].get(pull_action, pull_action)
    sync = pull
    if apply and push_action is not None:
        sync = f"{pull} → {_SYNC_TEXT['push'].get(push_action, push_action)}"
    lines += ["", f"<i>🔄 {sync}</i>"]
    return "\n".join(lines)
