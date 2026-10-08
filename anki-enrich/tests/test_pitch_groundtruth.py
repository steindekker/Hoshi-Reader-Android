"""Audit: the accent implied by each existing Pitch Accent SVG should match
アクセント辞典v2's lookup for (Term, Reading) at a high rate.

Parses the downstep position from the SVG's circle heights (cy=5 high / 30 low)
and compares to the shipped index. Skips if the collection isn't present
(gitignored; run `anki-enrich` to populate var/collection.anki2)."""
import os
import re
import sqlite3

import pytest

from ankienrich import config
from ankienrich import paths as _paths
from ankienrich.jrp_fill.accent import AccentIndex
from ankienrich.pitch import primary_accent

DB = _paths.WORKING_COLLECTION
IDX = _paths.ACCENT_INDEX
FS = "\x1f"

pytestmark = pytest.mark.skipif(
    not (os.path.exists(DB) and os.path.exists(IDX)),
    reason="needs var/collection.anki2 + built index",
)

_CIRC = re.compile(r'<circle r="(\d)" cx="\d+" cy="(\d+)"')


def _accent_from_svg(svg: str):
    """Reconstruct downstep position from circle heights, or None if unparseable.

    A field may stack several graphs (separated by <br>); the primary is listed
    first, so compare against only the first <svg>...</svg> block.
    """
    first = svg.split("</svg>", 1)[0]
    circ = _CIRC.findall(first)
    if len(circ) < 2:
        return None
    highs = [cy == "5" for _r, cy in circ]   # last entry = particle slot
    if highs[0]:
        return 1                              # atamadaka
    if highs[-1]:
        return 0                              # heiban (particle stays high)
    hi = [i for i, h in enumerate(highs) if h]
    if not hi:
        return None
    return max(hi) + 1                        # nakadaka/odaka: drop after last high


def _con():
    con = sqlite3.connect(f"file:{DB}?immutable=1", uri=True)
    con.create_collation(
        "unicase",
        lambda a, b: (a.casefold() > b.casefold()) - (a.casefold() < b.casefold()),
    )
    return con


def test_dict_agrees_with_existing_svgs():
    con = _con()
    row = con.execute(
        "SELECT id FROM notetypes WHERE name=?", (config.NOTETYPE,)).fetchone()
    if row is None:
        pytest.skip(f"collection has no {config.NOTETYPE} notes")
    mid = row[0]
    names = [r[0] for r in con.execute(
        "SELECT name FROM fields WHERE ntid=? ORDER BY ord", (mid,))]
    ix = {n: i for i, n in enumerate(names)}
    idx = AccentIndex.load(IDX)

    total = found = agree = 0
    mism = []
    for (flds,) in con.execute("SELECT flds FROM notes WHERE mid=?", (mid,)):
        f = flds.split(FS)
        term = f[ix[config.TERM]]
        reading = f[ix[config.READING]]
        pa = f[ix[config.PITCH]]
        if "<svg" not in pa or not reading.strip():
            continue
        observed = _accent_from_svg(pa)
        if observed is None:
            continue
        total += 1
        pos = primary_accent(idx, term, reading)
        if pos is None:
            continue
        found += 1
        if pos == observed:
            agree += 1
        elif len(mism) < 25:
            mism.append((term, reading, observed, pos))

    print(f"\nobserved-parsed: {total}  dict-found: {found} ({100*found/total:.1f}%)  "
          f"agree: {agree} ({100*agree/found:.1f}% of found)")
    print("sample mismatches (term, reading, svg_accent, dict_accent):")
    for m in mism:
        print("  ", m)
    # Coverage and agreement thresholds (dict study predicts ~97% / ~99%+).
    assert found / total > 0.90
    assert agree / found > 0.95
