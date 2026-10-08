"""Telegram transport + run-report formatting."""
from __future__ import annotations

import html

import requests

from . import config

_API = "https://api.telegram.org"


def send_message(text: str) -> None:
    token, chat = config.telegram_credentials()
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


# AnkiWeb sync action code -> human text. Shared with the CLI's sync wording.
SYNC_TEXT = {
    "pull": {"none": "already up to date", "normal": "fetched changes",
             "download": "downloaded full collection"},
    "push": {"none": "nothing to upload", "normal": "uploaded changes",
             "upload": "uploaded full collection"},
}


# Per-pass glyphs. One emoji a line — enough to scan on a phone, not a parade.
_EMOJI = {"audio": "🔊", "loudness": "🎚", "pitch": "📈", "sentence": "📝",
          "furigana": "🔤", "defs": "📖", "frequency": "🔢", "examples": "💬",
          "cloze": "🧩"}

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

    steps = res["steps"]
    verdict = "applied + pushed" if apply else "dry run"
    lines = [f"<b>anki-enrich</b> · {verdict}",
             f"<i>{res['scanned']:,} cards scanned</i>", ""]

    audio = steps["audio"]
    if "skipped" in audio:
        lines.append(_skipped("audio", audio["skipped"]))
    elif apply:
        filled = sum(audio["filled"].values())
        lines.append(_line("audio", filled, _detail(
            (audio.get("unchanged", 0), "already store audio"),
            (audio["errors"], "errors"))))
    else:
        lines.append(_line("audio", audio["will_fill"],
                           _detail((audio["none"], "no match"))))

    loud = steps["loudness"]
    if "skipped" in loud:
        lines.append(_skipped("loudness", loud["skipped"]))
    elif apply:
        lines.append(_line("loudness", loud["normalized"], _detail(
            # 0 is _detail's "drop this fragment" sentinel — no gain, no line noise
            (f"{loud['median_gain']:+.1f} dB" if loud["median_gain"] else 0, "median"),
            (loud["unchanged"], "already level"),
            (loud["errors"], "errors"))))
    else:
        lines.append(_line("loudness", loud["to_normalize"],
                           _detail((loud["notes"], "cards"))))

    lines.append(_line("sentence", steps["sentences"]["filled"]))
    lines.append(_line("furigana", steps["furigana"]["fixed"]))

    fields = steps["fields"]
    if "skipped" in fields:
        lines.append(_skipped("defs", fields["skipped"]))
    else:
        lines.append(_line("defs", fields["defs"], _detail(
            (fields["dropped"], "dropped") if fields["dropped"] else (fields["nohit"], "no-hit"))))
        lines.append(_line("examples", fields["examples"], _detail((fields["no_example"], "none"))))
        lines.append(_line("pitch", fields["pitch"], _detail((fields["pitch_cleared"], "cleared"))))
        lines.append(_line("frequency", fields["frequency"]))

    cloze = steps["cloze"]
    lines.append(_line("cloze", cloze["filled"], _detail(
        (cloze.get("resynced", 0), "resynced"),
        (cloze["skipped"], "unlocatable"),
        (cloze.get("mismatched", 0), "flagged"))))

    terms = steps.get("fields", {}).get("terms") or []
    if terms:
        shown = "、".join(html.escape(t) for t in terms[:_TERM_CAP])
        if len(terms) > _TERM_CAP:
            shown += f" ＋{len(terms) - _TERM_CAP}"
        lines += ["", f"🆕 <i>{shown}</i>"]

    pull = SYNC_TEXT["pull"].get(pull_action, pull_action)
    sync = pull
    if apply and push_action is not None:
        sync = f"{pull} → {SYNC_TEXT['push'].get(push_action, push_action)}"
    lines += ["", f"<i>🔄 {sync}</i>"]
    return "\n".join(lines)
