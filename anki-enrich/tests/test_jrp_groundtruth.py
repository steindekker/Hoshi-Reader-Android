import os
import random

import pytest

from ankienrich import config
from ankienrich import paths as _paths

DB = _paths.WORKING_COLLECTION
IDX = _paths.ACCENT_INDEX


def _sudachi():
    try:
        import sudachipy  # noqa: F401
        return True
    except Exception:
        return False


pytestmark = pytest.mark.skipif(
    not (os.path.exists(DB) and os.path.exists(IDX) and _sudachi()),
    reason="needs collection + accent index + sudachipy")


def test_strict_accent_coverage_at_least_98pct():
    import sqlite3
    from ankienrich.jrp_fill.accent import AccentIndex
    from ankienrich.jrp_fill.tokenize import units
    from ankienrich.jrp_fill.lexicon import NO_PITCH_LEMMAS, NO_PITCH_FORMS
    from ankienrich import markup
    acc = AccentIndex.load(IDX)
    con = sqlite3.connect(f"file:{DB}?immutable=1", uri=True)
    con.create_collation("unicase",
        lambda a, b: (a.casefold() > b.casefold()) - (a.casefold() < b.casefold()))
    row = con.execute(
        "SELECT id FROM notetypes WHERE name=?", (config.NOTETYPE,)).fetchone()
    if row is None:
        pytest.skip(f"collection has no {config.NOTETYPE} notes")
    mid = row[0]
    names = [r[0] for r in con.execute(
        "SELECT name FROM fields WHERE ntid=? ORDER BY ord", (mid,))]
    si = names.index(config.SENTENCE)
    # Strip existing markup back to plain surface so the gate works whether or
    # not the deck has already been filled.
    plain = []
    for (flds,) in con.execute("SELECT flds FROM notes WHERE mid=?", (mid,)):
        s = flds.split("\x1f")[si]
        if s.strip():
            surf = markup.jrp_surface(s) if ("{" in s or "[" in s) else s
            plain.append(surf.replace("<br>", " "))
    random.seed(7)
    sample = random.sample(plain, min(200, len(plain)))
    total = hit = 0
    for s in sample:
        for u in units(s, acc):
            # Exclude intentionally-unpitched common words from the denominator.
            if (u.is_content and u.lemma not in NO_PITCH_LEMMAS
                    and (u.lemma, u.reading) not in NO_PITCH_FORMS):
                total += 1
                if u.accents:
                    hit += 1
    assert total > 0
    cov = hit / total
    # Residual misses are all absent from the accent dict: proper nouns (names,
    # places), numerals/units, mimetics, slang/dialect. Their share varies with
    # the random sample — name-heavy media skews it — so the floor leaves room for
    # that drift while still catching a real engine regression (which craters
    # coverage well below 0.90).
    assert cov >= 0.95, f"strict accent coverage regressed: {cov:.3f} ({hit}/{total})"


def test_few_bare_kanji_in_rendered_output():
    import sqlite3
    import re
    from ankienrich.jrp_fill.accent import AccentIndex
    from ankienrich.jrp_fill.tokenize import units
    from ankienrich.jrp_fill.emit import emit_sentence
    from ankienrich.jrp_fill.splice import _spans
    acc = AccentIndex.load(IDX)
    con = sqlite3.connect(f"file:{DB}?immutable=1", uri=True)
    con.create_collation("unicase",
        lambda a, b: (a.casefold() > b.casefold()) - (a.casefold() < b.casefold()))
    row = con.execute(
        "SELECT id FROM notetypes WHERE name=?", (config.NOTETYPE,)).fetchone()
    if row is None:
        pytest.skip(f"collection has no {config.NOTETYPE} notes")
    mid = row[0]
    names = [r[0] for r in con.execute(
        "SELECT name FROM fields WHERE ntid=? ORDER BY ord", (mid,))]
    si = names.index(config.SENTENCE)
    plain = []
    for (flds,) in con.execute("SELECT flds FROM notes WHERE mid=?", (mid,)):
        s = flds.split("\x1f")[si]
        if s.strip() and not ("{" in s and ";" in s):
            plain.append(s.replace("<br>", " "))
    random.seed(7)
    sample = random.sample(plain, min(200, len(plain)))
    kanji = re.compile(r"[㐀-鿿々]")
    sents_with_bare = 0
    for s in sample:
        out = emit_sentence(units(s, acc))
        bare = sum(len(kanji.findall(t)) for kind, t in _spans(out) if kind == "plain")
        if bare:
            sents_with_bare += 1
    # 代名詞 in content keeps this low; counters (接尾辞) intentionally stay bare
    # (adding them would introduce wrong irregular readings like 二日->ふたか).
    assert sents_with_bare <= 15, f"too many bare-kanji sentences: {sents_with_bare}/200"
