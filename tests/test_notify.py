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


def _dry_res():
    return {"scanned": 1234, "backup": None, "steps": {
        "audio": {"will_fill": 12, "will_upgrade": 3, "none": 4},
        "pitch": {"filled": 8, "not_found": 2, "skipped": 1},
        "sentences": {"filled": 5},
        "enrich": {"to_write": 10, "nohit": 1, "terms": ["猫", "犬"]},
        "examples": {"to_write": 7, "none": 2},
        "cloze": {"filled": 4, "skipped": 1}}}


def _apply_res():
    return {"scanned": 1234, "backup": "var/backup.colpkg", "steps": {
        "audio": {"filled": {"nhk": 9, "forvo": 3}, "upgraded": {"nhk": 3}},
        "pitch": {"filled": 8, "not_found": 2, "skipped": 1},
        "sentences": {"filled": 5},
        "enrich": {"written": 10, "dropped": 0, "terms": ["猫", "犬"]},
        "examples": {"written": 7, "none_tagged": 2},
        "cloze": {"filled": 4, "skipped": 1}}}


def test_format_report_dry_run():
    out = notify.format_report(_dry_res(), apply=False, pull_action="none")
    assert out.startswith("<b>anki-enrich</b>")  # plain bold title, no eyes
    assert "👀" not in out
    assert "dry run" in out
    assert "1,234 cards scanned" in out          # thousands separator
    assert "sentence" in out and "<b>5</b>" in out


def test_format_report_lists_new_terms():
    out = notify.format_report(_dry_res(), apply=False, pull_action="none")
    assert "猫、犬" in out  # new (defined) terms, inline


def test_format_report_caps_term_list():
    res = _dry_res()
    res["steps"]["enrich"]["terms"] = [f"語{i}" for i in range(25)]
    out = notify.format_report(res, apply=False, pull_action="none")
    assert "語0" in out and "語19" in out
    assert "語20" not in out
    assert "＋5" in out  # overflow indicator


def test_format_report_omits_term_line_when_empty():
    res = _dry_res()
    res["steps"]["enrich"]["terms"] = []
    out = notify.format_report(res, apply=False, pull_action="none")
    assert "🆕" not in out


def test_format_report_apply_has_verdict_and_sync():
    out = notify.format_report(
        _apply_res(), apply=True, pull_action="normal", push_action="normal")
    assert "applied + pushed" in out
    assert "<b>12</b>" in out  # 9 + 3 audio filled
    assert "uploaded changes" in out  # push action rendered in sync footer


def test_format_report_drops_zero_detail():
    # defs written with 0 dropped -> no trailing "0 dropped" noise
    out = notify.format_report(
        _apply_res(), apply=True, pull_action="normal", push_action="normal")
    assert "dropped" not in out


def test_format_report_skipped_step_shows_reason():
    res = _dry_res()
    res["steps"]["audio"] = {"skipped": "entries.db not found"}
    out = notify.format_report(res, apply=False, pull_action="none")
    assert "entries.db not found" in out


def test_format_report_failure_names_pass_and_escapes():
    out = notify.format_report(
        None, apply=True, pull_action=None,
        error=("defs", RuntimeError("missing <db>")))
    assert "failed at defs" in out
    assert "RuntimeError: missing &lt;db&gt;" in out  # HTML-escaped
