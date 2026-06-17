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
