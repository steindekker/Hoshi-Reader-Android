import os, pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankialign.jrp_fill import engine

def _make_col(tmp_path):
    col = Collection(str(tmp_path / "col.anki2"))
    m = col.models.new("jidoujisho Kinomoto")
    for f in ("Term", "Sentence"):
        col.models.add_field(m, col.models.new_field(f))
    tmpl = col.models.new_template("Card 1")
    tmpl["qfmt"] = "{{Sentence}}"; tmpl["afmt"] = "{{Sentence}}"
    col.models.add_template(m, tmpl)
    col.models.add(m)
    return col, m

def _add(col, m, term, sentence):
    n = col.new_note(m)
    n["Term"] = term; n["Sentence"] = sentence
    col.add_note(n, col.decks.id("Default"))
    return n.id

def test_plan_classifies_and_generates(tmp_path):
    col, m = _make_col(tmp_path)
    plain = _add(col, m, "異端", "異端である")
    marked = _add(col, m, "異変", "{[異変|いへん];0}だ")
    empty = _add(col, m, "x", "")
    # stub generate: valid JRP whose surface reproduces the input
    plan = engine.build_plan(col, "jidoujisho Kinomoto", "Sentence",
                             generate=lambda t: "{" + t + ";0}")
    by_id = {p.note_id: p for p in plan}
    assert by_id[plain].new_value == "{異端である;0}"
    assert marked not in by_id            # fully marked -> no change -> skipped
    assert empty not in by_id             # empty -> skipped
    col.close()

def test_apply_writes_through_col(tmp_path):
    col, m = _make_col(tmp_path)
    nid = _add(col, m, "異端", "異端である")
    plan = engine.build_plan(col, "jidoujisho Kinomoto", "Sentence",
                             generate=lambda t: "{" + t + ";0}")
    engine.apply_plan(col, plan)
    assert col.get_note(nid)["Sentence"] == "{異端である;0}"
    col.close()

def test_guard_rejects_surface_changing_fill(tmp_path):
    col, m = _make_col(tmp_path)
    _add(col, m, "異端", "異端である")
    # generate returns markup whose surface differs from the input -> must be rejected
    plan = engine.build_plan(
        col, "jidoujisho Kinomoto", "Sentence",
        generate=lambda t: "{異なる;0}",
    )
    assert len(plan) == 0, "round-trip guard must block surface-changing fills"
    col.close()
