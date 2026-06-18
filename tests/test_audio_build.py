from ankienrich.audio import build


def test_ensure_present_when_store_exists(tmp_path):
    db = tmp_path / "audio.sqlite"
    db.write_text("")  # any existing file
    assert build.ensure(db_path=str(db)) == "present"


def test_ensure_no_source_when_missing(tmp_path):
    db = tmp_path / "audio.sqlite"
    assert build.ensure(db_path=str(db)) == "no-source"
    assert not db.exists()
