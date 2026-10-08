import os

from anki.collection import Collection

from ankienrich import collection, config


def test_notetype_search_shape():
    s = collection.notetype_search()
    assert s == f'note:"{config.NOTETYPE}"'


def test_backup_creates_fresh_colpkg(tmp_path):
    col = Collection(os.path.join(tmp_path, "c.anki2"))
    try:
        bdir = collection.backup(col, str(tmp_path / "backups"))
        pkgs = [f for f in os.listdir(bdir) if f.endswith(".colpkg")]
        assert pkgs, "expected a .colpkg backup"
    finally:
        col.close()
