import argparse

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
            "audio": {"skipped": "x"}, "pitch": {"filled": 0, "not_found": 0, "skipped": 0},
            "sentences": {"filled": 0}, "enrich": {"skipped": "x"},
            "examples": {"skipped": "x"}, "cloze": {"filled": 0, "skipped": 0}}}

    class FakeCol:
        def close(self): pass

    monkeypatch.setattr(cli.collection, "open_working", lambda: FakeCol())
    monkeypatch.setattr(cli.sync, "login", lambda col: None)
    monkeypatch.setattr(cli.sync, "pull", lambda col, auth: ("none", auth))
    monkeypatch.setattr(cli.pipeline, "run", fake_run)
    cli.main(["--overwrite"])
    assert captured["overwrite_pitch"] and captured["overwrite_cloze"]
    assert captured["overwrite_sentences"] and captured["overwrite_defs"]
    assert captured["overwrite_examples"]


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
        overwrite_pitch=False, overwrite_sentences=False, overwrite_defs=False,
        overwrite_examples=False, overwrite_cloze=False)


def test_wizard_drives_main_when_triggered(monkeypatch):
    captured = {}

    def fake_run(col, **kw):
        captured["called"] = True
        return {"scanned": 0, "backup": None, "steps": {
            "audio": {"skipped": "x"}, "pitch": {"filled": 0, "not_found": 0, "skipped": 0},
            "sentences": {"filled": 0}, "enrich": {"skipped": "x"},
            "examples": {"skipped": "x"}, "cloze": {"filled": 0, "skipped": 0}}}

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
