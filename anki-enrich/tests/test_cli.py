import argparse

import pytest

from ankienrich import cli


def test_template_dry_run_does_not_apply(monkeypatch):
    applied = []

    class FakeCol:
        def close(self): pass

    monkeypatch.setattr(cli.collection, "open_working", lambda: FakeCol())
    monkeypatch.setattr(cli.sync, "login", lambda col: None)
    monkeypatch.setattr(cli.sync, "pull", lambda col, auth: ("none", auth))
    monkeypatch.setattr(cli.template, "apply", lambda col: applied.append(True))
    cli.main(["template"])
    assert applied == [], "template.apply must not be called on dry-run"


def test_overwrite_fans_out(monkeypatch):
    captured = {}

    def fake_run(col, **kw):
        captured.update(kw)
        return {"scanned": 0, "backup": None, "steps": {
            "audio": {"skipped": "x"}, "loudness": {"skipped": "x"}, 
            "sentences": {"filled": 0}, "furigana": {"fixed": 0}, "fields": {"skipped": "x"},
            "cloze": {"filled": 0, "skipped": 0}}}

    class FakeCol:
        def close(self): pass

    monkeypatch.setattr(cli.collection, "open_working", lambda: FakeCol())
    monkeypatch.setattr(cli.sync, "login", lambda col: None)
    monkeypatch.setattr(cli.sync, "pull", lambda col, auth: ("none", auth))
    monkeypatch.setattr(cli.pipeline, "run", fake_run)
    cli.main(["--overwrite"])
    assert captured["overwrite_cloze"]
    assert captured["overwrite_sentences"] and captured["overwrite_fields"]
    assert "overwrite_pitch" not in captured   # pitch is always re-asserted


def test_dry_run_flag_is_accepted_and_does_not_apply(monkeypatch):
    applied = []

    class FakeCol:
        def close(self): pass

    monkeypatch.setattr(cli.collection, "open_working", lambda: FakeCol())
    monkeypatch.setattr(cli.sync, "login", lambda col: None)
    monkeypatch.setattr(cli.sync, "pull", lambda col, auth: ("none", auth))
    monkeypatch.setattr(cli.template, "apply", lambda col: applied.append(True))
    assert cli.main(["template", "--dry-run"]) == 0
    assert applied == []


def _apply_run_namespace():
    return argparse.Namespace(
        command="run", apply=True, overwrite=False,
        overwrite_sentences=False, overwrite_fields=False, overwrite_cloze=False)


def test_wizard_drives_main_when_triggered(monkeypatch):
    captured = {}

    def fake_run(col, **kw):
        captured["called"] = True
        return {"scanned": 0, "backup": None, "steps": {
            "audio": {"skipped": "x"}, "loudness": {"skipped": "x"}, 
            "sentences": {"filled": 0}, "furigana": {"fixed": 0}, "fields": {"skipped": "x"},
            "cloze": {"filled": 0, "skipped": 0}}}

    class FakeCol:
        def close(self): pass

    monkeypatch.setattr(cli.collection, "open_working", lambda: FakeCol())
    monkeypatch.setattr(cli.sync, "login", lambda col: None)
    monkeypatch.setattr(cli.sync, "pull", lambda col, auth: ("none", auth))
    monkeypatch.setattr(cli.sync, "push", lambda col, auth: ("none", auth))
    monkeypatch.setattr(cli.collection, "backup", lambda col: None)
    monkeypatch.setattr(cli.pipeline, "run", fake_run)
    monkeypatch.setattr(cli.wizard, "should_run_wizard", lambda argv, isatty: True)
    monkeypatch.setattr(cli.wizard, "run", lambda dry_run: _apply_run_namespace())
    assert cli.main([]) == 0
    assert captured.get("called") is True


def test_wizard_quit_is_clean_noop(monkeypatch):
    opened = []

    class FakeCol:
        def close(self): pass

    monkeypatch.setattr(cli.collection, "open_working",
                        lambda: opened.append(True) or FakeCol())
    monkeypatch.setattr(cli.wizard, "should_run_wizard", lambda argv, isatty: True)
    monkeypatch.setattr(cli.wizard, "run", lambda dry_run: None)
    assert cli.main([]) == 0
    assert opened == [], "quitting the wizard must not open the collection"


def _ok_res():
    return {"scanned": 0, "backup": None, "steps": {
        "audio": {"skipped": "x"}, "loudness": {"skipped": "x"}, 
        "sentences": {"filled": 0}, "furigana": {"fixed": 0}, "fields": {"skipped": "x"},
        "cloze": {"filled": 0, "skipped": 0}}}


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
    assert sent and "failed at defs" in sent[0]
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
