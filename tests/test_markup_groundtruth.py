import os
import sqlite3

import pytest

from ankienrich import markup
from ankienrich import paths as _paths

DB = _paths.WORKING_COLLECTION
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
    row = con.execute(
        "SELECT id FROM notetypes WHERE name='jidoujisho Yuuna'").fetchone()
    if row is None:
        pytest.skip("collection has no jidoujisho Yuuna notes")
    mid = row[0]
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
    # The only mismatch in this small sample (n~15) is a genuine collection data
    # conflict — 大人気 has jrp markup おとなげ but stored Reading だいにんき (both valid).
    # Parser is correct; this guard still catches real regressions.
    assert match / total >= 0.90, f"{match}/{total} readings matched"
