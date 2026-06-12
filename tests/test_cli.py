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
