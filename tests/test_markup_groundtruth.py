# tests/test_markup_groundtruth.py
import os, sqlite3
import pytest
from ankialign import markup

DB = os.path.join(os.path.dirname(__file__), "..", "collection.anki2")
FS = "\x1f"

pytestmark = pytest.mark.skipif(not os.path.exists(DB), reason="no collection.anki2")


def _con():
    con = sqlite3.connect(f"file:{DB}?immutable=1", uri=True)
    con.create_collation(
        "unicase",
        lambda a, b: (a.casefold() > b.casefold()) - (a.casefold() < b.casefold()),
    )
    return con


def test_jrp_reading_matches_yuuna_reading_field():
    """For Yuuna notes whose Term carries jrp markup, derived reading should
    match the stored Reading field at a high rate."""
    con = _con()
    mid = con.execute(
        "SELECT id FROM notetypes WHERE name='jidoujisho Yuuna'"
    ).fetchone()[0]
    names = [r[0] for r in con.execute(
        "SELECT name FROM fields WHERE ntid=? ORDER BY ord", (mid,))]
    idx = {n: i for i, n in enumerate(names)}
    total = match = 0
    for (flds,) in con.execute("SELECT flds FROM notes WHERE mid=?", (mid,)):
        f = flds.split(FS)
        term, reading = f[idx["Term"]], f[idx["Reading"]]
        if "{" not in term or not reading.strip():
            continue
        total += 1
        if markup.jrp_reading(term) == reading.strip():
            match += 1
    assert total > 0
    assert match / total >= 0.95, f"{match}/{total} readings matched"
