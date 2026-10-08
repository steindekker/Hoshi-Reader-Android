"""Tests for `anki-enrich add`: dry-run, dedup, apply+enrich-one, lock."""
from __future__ import annotations

import fcntl
import os
from contextlib import contextmanager

import pytest

from ankienrich import addcli, addnotes, config, runlock
from ankienrich.addnotes import NoteSpec
from ankienrich.collection import open_working


@pytest.fixture
def col_path(tmp_path, monkeypatch):
    monkeypatch.setenv("ANKI_ENRICH_VAR", str(tmp_path / "var"))
    return str(tmp_path / "collection.anki2")


@pytest.fixture
def open_col(col_path):
    def _open():
        return open_working(col_path)

    c = open_working(col_path)
    addnotes.ensure_notetype(c)
    c.close()
    return _open


@pytest.fixture
def noop_sync():
    sentinel = object()

    def login(col):
        return sentinel

    def pull(col, auth):
        return "none", auth

    def push(col, auth):
        return "none", auth

    return {"login": login, "pull": pull, "push": push}


@pytest.fixture
def noop_enrich():
    calls = []

    def enrich_fn(col, *, note_ids):
        calls.append(list(note_ids))
        return {"scanned": len(note_ids), "backup": None, "steps": {}}

    enrich_fn.calls = calls
    return enrich_fn


def test_dry_run_preview(open_col, capsys):
    spec = NoteSpec(term="食べる", reading="たべる", sentence="寿司を食べる。")
    rc = addcli.run(spec, apply=False, open_col=open_col)
    assert rc == 0
    out = capsys.readouterr().out
    assert "dry-run" in out
    assert "食べる" in out
    assert "たべる" in out
    assert "寿司を食べる。" in out
    assert "dedup:    ok" in out
    assert "enrich-that-note" in out
    col = open_col()
    try:
        assert addnotes.find_duplicates(col, spec) == []
    finally:
        col.close()


def test_dry_run_cli_main(open_col, monkeypatch, capsys):
    monkeypatch.setattr(addcli.collection, "open_working", open_col)
    assert addcli.main(["漢字", "--furigana", "漢[かん]字[じ]"]) == 0
    out = capsys.readouterr().out
    assert "dry-run" in out
    assert "漢字" in out


def test_dedup_term_only(open_col, noop_sync, noop_enrich, capsys):
    spec = NoteSpec(term="水")
    col = open_col()
    try:
        addnotes.add_note(col, spec)
    finally:
        col.close()

    rc = addcli.run(spec, apply=False, open_col=open_col)
    assert rc == 0
    assert "DUPLICATE" in capsys.readouterr().out

    rc = addcli.run(
        spec, apply=True, open_col=open_col, sync_ops=noop_sync, enrich_fn=noop_enrich
    )
    assert rc == 2
    err = capsys.readouterr().err
    assert "refusing" in err or "duplicate" in err.lower()


def test_dedup_term_reading(open_col, noop_sync, noop_enrich, capsys):
    existing = NoteSpec(term="行", reading="こう")
    other = NoteSpec(term="行", reading="ぎょう")
    col = open_col()
    try:
        addnotes.add_note(col, existing)
    finally:
        col.close()

    assert addcli.run(
        existing, apply=True, open_col=open_col, sync_ops=noop_sync, enrich_fn=noop_enrich
    ) == 2
    assert addcli.run(
        other, apply=True, open_col=open_col, sync_ops=noop_sync, enrich_fn=noop_enrich
    ) == 0
    out = capsys.readouterr().out
    assert "added:" in out
    assert "enrich:" in out


def test_apply_adds_and_enriches_one(open_col, noop_sync, noop_enrich, capsys):
    spec = NoteSpec(
        term="勉強",
        reading="べんきょう",
        furigana="勉[べん]強[きょう]",
        sentence="日本語を勉強する。",
        deck="Japanese",
    )
    rc = addcli.run(
        spec, apply=True, open_col=open_col, sync_ops=noop_sync, enrich_fn=noop_enrich
    )
    assert rc == 0
    out = capsys.readouterr().out
    assert "mode:     apply" in out
    assert "pull" in out
    assert "added:" in out
    assert "enrich:" in out
    assert "push" in out
    assert len(noop_enrich.calls) == 1
    assert len(noop_enrich.calls[0]) == 1

    col = open_col()
    try:
        ids = addnotes.find_duplicates(col, spec)
        assert ids == noop_enrich.calls[0]
        note = col.get_note(ids[0])
        assert note[config.TERM] == "勉強"
        assert note[config.READING] == "べんきょう"
        assert note[config.DEFINITION] == ""
        assert note[config.PITCH] == ""
    finally:
        col.close()


def test_apply_idempotent_refuse(open_col, noop_sync, noop_enrich):
    spec = NoteSpec(term="猫", reading="ねこ")
    assert addcli.run(
        spec, apply=True, open_col=open_col, sync_ops=noop_sync, enrich_fn=noop_enrich
    ) == 0
    assert addcli.run(
        spec, apply=True, open_col=open_col, sync_ops=noop_sync, enrich_fn=noop_enrich
    ) == 2


def _png(tmp_path, name="asagao.png") -> str:
    path = tmp_path / name
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\0" * 32)
    return str(path)


def test_validate_image_relative_refused(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    (tmp_path / "rel.png").write_bytes(b"x")
    with pytest.raises(ValueError, match="absolute"):
        addcli.validate_image_path("rel.png")


def test_apply_image(open_col, noop_sync, noop_enrich, tmp_path, capsys):
    path = _png(tmp_path, "mine_shot.png")
    spec = NoteSpec(term="朝顔", reading="あさがお", image_path=path)
    rc = addcli.run(
        spec, apply=True, open_col=open_col, sync_ops=noop_sync, enrich_fn=noop_enrich
    )
    assert rc == 0
    col = open_col()
    try:
        ids = addnotes.find_duplicates(col, spec)
        note = col.get_note(ids[0])
        assert note[config.IMAGE] == '<img src="mine_shot.png">'
        assert "mine_shot.png" in os.listdir(col.media.dir())
    finally:
        col.close()


def test_apply_refuses_when_lock_held(open_col, noop_sync, noop_enrich, col_path, capsys):
    # col_path fixture set ANKI_ENRICH_VAR to tmp/var
    from ankienrich import paths
    lock_path = os.path.join(paths.var_dir(), "run.lock")
    os.makedirs(paths.var_dir(), exist_ok=True)
    f = open(lock_path, "a+")
    fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
    try:
        spec = NoteSpec(term="鍵", reading="かぎ")
        rc = addcli.run(
            spec, apply=True, open_col=open_col, sync_ops=noop_sync, enrich_fn=noop_enrich
        )
        assert rc == 3
        err = capsys.readouterr().err
        assert "run.lock" in err
    finally:
        fcntl.flock(f, fcntl.LOCK_UN)
        f.close()


def test_cli_add_dispatch(monkeypatch, open_col, capsys):
    from ankienrich import cli

    monkeypatch.setattr(addcli.collection, "open_working", open_col)
    assert cli.main(["add", "花", "--reading", "はな"]) == 0
    out = capsys.readouterr().out
    assert "dry-run" in out
    assert "花" in out


def test_pipeline_note_ids_scopes(tmp_path, monkeypatch):
    """pipeline.run(note_ids=[…]) only scans those notes."""
    from anki.collection import Collection
    from ankienrich import pipeline
    from ankienrich.audio import build as audio_build
    from ankienrich.defs import build as def_build
    from ankienrich.examples import build as ex_build
    from ankienrich.freq import build as freq_build

    monkeypatch.setattr(audio_build, "ensure", lambda: "no-source")
    monkeypatch.setattr(def_build, "ensure", lambda: "no-source")
    monkeypatch.setattr(ex_build, "ensure", lambda: "no-source")
    monkeypatch.setattr(freq_build, "ensure", lambda: "no-source")

    col = Collection(str(tmp_path / "c.anki2"))
    try:
        addnotes.ensure_notetype(col)
        n1 = addnotes.add_note(col, NoteSpec(term="一"))
        n2 = addnotes.add_note(col, NoteSpec(term="二"))
        res_all = pipeline.run(col, apply=False)
        assert res_all["scanned"] == 2
        res_one = pipeline.run(col, apply=False, note_ids=[n1])
        assert res_one["scanned"] == 1
        res_none = pipeline.run(col, apply=False, note_ids=[])
        assert res_none["scanned"] == 0
    finally:
        col.close()
