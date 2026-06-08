import os

from anki.collection import Collection

from ankialign import collection, config


def test_in_scope_search_shape():
    s = collection.in_scope_search()
    assert s.startswith(f'note:"{config.NOTETYPE}"')
    assert "-deck:*Core2.3k*" in s


def test_backup_creates_fresh_colpkg(tmp_path):
    col = Collection(os.path.join(tmp_path, "c.anki2"))
    try:
        bdir = collection.backup(col, str(tmp_path / "backups"))
        pkgs = [f for f in os.listdir(bdir) if f.endswith(".colpkg")]
        assert pkgs, "expected a .colpkg backup"
    finally:
        col.close()
