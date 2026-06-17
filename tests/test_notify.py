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
