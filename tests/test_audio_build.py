import sqlite3

from ankienrich.audio import build, enrich


def _fake_addon(tmp_path):
    """A minimal local-audio-yomichan user_files dir: entries.db + 2 clips."""
    uf = tmp_path / "1045800357" / "user_files"
    (uf).mkdir(parents=True)
    con = sqlite3.connect(str(uf / "entries.db"))
    con.execute("CREATE TABLE entries (id integer PRIMARY KEY, expression text, "
                "reading text, source text, speaker text, display text, file text)")
    con.executemany(
        "INSERT INTO entries (expression, reading, source, speaker, file) VALUES (?,?,?,?,?)",
        [
            ("橋", "はし", "nhk16", None, "audio/n.opus"),
            ("橋", "はし", "shinmeikai8", None, "media/s.opus"),
            ("筋トレ", None, "forvo", "strawberrybrown", "strawberrybrown/b.opus"),
            ("欠番", "けつばん", "jpod", None, "media/missing.opus"),  # file absent on disk
        ])
    con.commit()
    con.close()
    for src, rel, blob in [
        ("nhk16", "audio/n.opus", b"NHK-bytes"),
        ("shinmeikai8", "media/s.opus", b"SMK-bytes"),
        ("forvo", "strawberrybrown/b.opus", b"FORVO-bytes"),
    ]:
        f = uf / f"{src}_files" / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_bytes(blob)
    return tmp_path  # the addons dir


def test_build_creates_entries_and_clips(tmp_path):
    addons = _fake_addon(tmp_path)
    db = tmp_path / "audio.sqlite"
    counts = build.build(str(addons / "1045800357" / "user_files"), str(db))

    con = sqlite3.connect(str(db))
    # all entries rows copied (4), clips only for files present on disk (3)
    assert con.execute("SELECT COUNT(*) FROM entries").fetchone()[0] == 4
    assert con.execute("SELECT COUNT(*) FROM clips").fetchone()[0] == 3
    assert counts == {"entries": 4, "clips": 3, "missing": 1}

    # lookup + read_clip work end to end against the built store
    pick = enrich.lookup(con, "橋", "はし")
    assert pick == enrich.Pick("nhk16", "audio/n.opus")
    assert enrich.read_clip(con, pick) == b"NHK-bytes"
    # jpod row exists but its blob is absent (file was missing)
    jp = enrich.Pick("jpod", "media/missing.opus")
    assert enrich.read_clip(con, jp) is None
    con.close()


def test_ensure_present_when_db_exists(tmp_path):
    db = tmp_path / "audio.sqlite"
    db.write_text("")  # any existing file
    assert build.ensure(addons_dir=str(tmp_path / "nope"), db_path=str(db)) == "present"


def test_ensure_no_source_when_addon_missing(tmp_path):
    db = tmp_path / "audio.sqlite"
    assert build.ensure(addons_dir=str(tmp_path / "empty"), db_path=str(db)) == "no-source"
    assert not db.exists()


def test_ensure_builds_from_addon(tmp_path):
    addons = _fake_addon(tmp_path)
    db = tmp_path / "audio.sqlite"
    assert build.ensure(addons_dir=str(addons), db_path=str(db)) == "built"
    con = sqlite3.connect(str(db))
    assert con.execute("SELECT COUNT(*) FROM clips").fetchone()[0] == 3
    con.close()
