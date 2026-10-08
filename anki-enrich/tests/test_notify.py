import pytest

from ankienrich import notify


def test_send_message_posts_expected_payload(monkeypatch):
    import ankienrich.notify as _notify
    sent = {}

    class _Resp:
        status_code = 200
        def raise_for_status(self): pass

    def _post(url, **kw):
        sent["url"] = url
        sent["json"] = kw.get("json")
        return _Resp()

    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "TOK")
    monkeypatch.setenv("TELEGRAM_CHAT_ID", "42")
    monkeypatch.setattr(_notify.requests, "post", _post)

    notify.send_message("hello")

    assert sent["url"] == "https://api.telegram.org/botTOK/sendMessage"
    assert sent["json"] == {"chat_id": "42", "text": "hello", "parse_mode": "HTML"}


def test_send_message_surfaces_telegram_error(monkeypatch):
    import ankienrich.notify as _notify
    import requests as req_lib

    class _ErrResp:
        status_code = 400
        def json(self):
            return {"ok": False, "description": "Bad Request: chat not found"}

    def _post(url, **kw):
        resp = _ErrResp()
        raise req_lib.HTTPError(response=resp)

    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "TOK")
    monkeypatch.setenv("TELEGRAM_CHAT_ID", "42")
    monkeypatch.setattr(_notify.requests, "post", _post)

    with pytest.raises(RuntimeError, match="chat not found"):
        notify.send_message("hello")


def test_send_message_requires_env(monkeypatch):
    monkeypatch.delenv("TELEGRAM_BOT_TOKEN", raising=False)
    monkeypatch.delenv("TELEGRAM_CHAT_ID", raising=False)
    with pytest.raises(RuntimeError):
        notify.send_message("hello")


def test_send_message_posts_via_requests(monkeypatch):
    import ankienrich.notify as notify
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "T")
    monkeypatch.setenv("TELEGRAM_CHAT_ID", "42")
    calls = {}
    class _Resp:
        status_code = 200
        def raise_for_status(self): pass
    def _post(url, **kw):
        calls["url"] = url
        calls["json"] = kw.get("json")
        calls["timeout"] = kw.get("timeout")
        return _Resp()
    monkeypatch.setattr(notify.requests, "post", _post)
    notify.send_message("hi")
    assert calls["url"].endswith("/botT/sendMessage")
    assert calls["json"]["chat_id"] == "42"
    assert calls["json"]["text"] == "hi"
    assert calls["timeout"] is not None


def _fields(dropped):
    return {"defs": 10, "examples": 7, "no_example": 2, "pitch": 8, "pitch_cleared": 3,
            "frequency": 6, "notes": 20, "nohit": dropped, "dropped": dropped,
            "terms": ["猫", "犬"]}


def _dry_res():
    return {"scanned": 1234, "backup": None, "steps": {
        "audio": {"will_fill": 12, "by_source": {"nhk16": 8, "forvo": 4}, "none": 4, "missing_file": 0},
        "loudness": {"to_normalize": 15, "notes": 14},
        "sentences": {"filled": 5},
        "furigana": {"fixed": 3},
        "fields": _fields(dropped=1),
        "cloze": {"filled": 4, "skipped": 1}}}


def _apply_res():
    return {"scanned": 1234, "backup": "var/backup.colpkg", "steps": {
        "audio": {"filled": {"nhk16": 9, "forvo": 3}, "errors": 0},
        "loudness": {"normalized": 15, "notes": 14, "unchanged": 2, "missing": 0,
                     "median_gain": 3.4, "errors": 0},
        "sentences": {"filled": 5},
        "furigana": {"fixed": 3},
        "fields": _fields(dropped=0),
        "cloze": {"filled": 4, "skipped": 1}}}


def test_format_report_dry_run():
    out = notify.format_report(_dry_res(), apply=False, pull_action="none")
    assert out.startswith("<b>anki-enrich</b>")  # plain bold title, no eyes
    assert "👀" not in out
    assert "dry run" in out
    assert "1,234 cards scanned" in out          # thousands separator
    assert "sentence" in out and "<b>5</b>" in out
    assert "furigana" in out and "<b>3</b>" in out  # furigana spacing fixes
    assert "frequency" in out and "<b>6</b>" in out  # JPDB rank fills


def test_format_report_lists_new_terms():
    out = notify.format_report(_dry_res(), apply=False, pull_action="none")
    assert "猫、犬" in out  # new (defined) terms, inline


def test_format_report_caps_term_list():
    res = _dry_res()
    res["steps"]["fields"]["terms"] = [f"語{i}" for i in range(25)]
    out = notify.format_report(res, apply=False, pull_action="none")
    assert "語0" in out and "語19" in out
    assert "語20" not in out
    assert "＋5" in out  # overflow indicator


def test_format_report_omits_term_line_when_empty():
    res = _dry_res()
    res["steps"]["fields"]["terms"] = []
    out = notify.format_report(res, apply=False, pull_action="none")
    assert "🆕" not in out


def test_format_report_apply_has_verdict_and_sync():
    out = notify.format_report(
        _apply_res(), apply=True, pull_action="normal", push_action="normal")
    assert "applied + pushed" in out
    assert "<b>12</b>" in out  # 9 + 3 audio filled
    assert "uploaded changes" in out  # push action rendered in sync footer
    assert "loudness" in out and "+3.4 dB median" in out


def test_format_report_omits_gain_when_nothing_was_levelled():
    res = _apply_res()
    res["steps"]["loudness"] = {"normalized": 0, "notes": 0, "unchanged": 0,
                                "missing": 0, "median_gain": 0.0, "errors": 0}
    out = notify.format_report(res, apply=True, pull_action="none", push_action="none")
    assert "dB median" not in out


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
