import os, random
import pytest

DB = os.path.join(os.path.dirname(__file__), "..", "collection.anki2")
IDX = os.path.join(os.path.dirname(__file__), "..",
                   "ankialign", "data", "accent_index.marshal.gz")

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
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    acc = AccentIndex.load(IDX)
    con = sqlite3.connect(f"file:{DB}?immutable=1", uri=True)
    con.create_collation("unicase",
        lambda a, b: (a.casefold() > b.casefold()) - (a.casefold() < b.casefold()))
    mid = con.execute(
        "SELECT id FROM notetypes WHERE name='jidoujisho Kinomoto'").fetchone()[0]
    names = [r[0] for r in con.execute(
        "SELECT name FROM fields WHERE ntid=? ORDER BY ord", (mid,))]
    si = names.index("Sentence")
    plain = []
    for (flds,) in con.execute("SELECT flds FROM notes WHERE mid=?", (mid,)):
        s = flds.split("\x1f")[si]
        if s.strip() and not ("{" in s and ";" in s):
            plain.append(s.replace("<br>", " "))
    random.seed(7)
    sample = random.sample(plain, min(200, len(plain)))
    total = hit = 0
    for s in sample:
        for u in units(s, acc):
            if u.is_content:
                total += 1
                if u.accents:
                    hit += 1
    assert total > 0
    cov = hit / total
    assert cov >= 0.98, f"strict accent coverage regressed: {cov:.3f} ({hit}/{total})"

def test_few_bare_kanji_in_rendered_output():
    import sqlite3, re
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    from ankialign.jrp_fill.splice import _spans
    acc = AccentIndex.load(IDX)
    con = sqlite3.connect(f"file:{DB}?immutable=1", uri=True)
    con.create_collation("unicase",
        lambda a, b: (a.casefold() > b.casefold()) - (a.casefold() < b.casefold()))
    mid = con.execute(
        "SELECT id FROM notetypes WHERE name='jidoujisho Kinomoto'").fetchone()[0]
    names = [r[0] for r in con.execute(
        "SELECT name FROM fields WHERE ntid=? ORDER BY ord", (mid,))]
    si = names.index("Sentence")
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
    # (adding them would introduce wrong irregular-counter readings like 二日->ふたか).
    assert sents_with_bare <= 15, f"too many bare-kanji sentences: {sents_with_bare}/200"
