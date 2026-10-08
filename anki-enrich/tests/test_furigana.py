import os, pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankienrich import config, furigana


def _col(tmp_path):
    col = Collection(str(tmp_path / "col.anki2"))
    m = col.models.new(config.NOTETYPE)
    for f in (config.TERM, config.TERM_FURIGANA, config.SENTENCE_FURIGANA):
        col.models.add_field(m, col.models.new_field(f))
    t = col.models.new_template("Card 1")
    t["qfmt"], t["afmt"] = "{{Term}}", "{{Term}}"
    col.models.add_template(m, t)
    col.models.add(m)
    return col, m


def _add(col, m, furi="", sfuri=""):
    n = col.new_note(m)
    n[config.TERM] = "x"; n[config.TERM_FURIGANA] = furi
    n[config.SENTENCE_FURIGANA] = sfuri
    col.add_note(n, col.decks.id("Default"))
    return n.id


def test_plan_fixes_broken_spacing(tmp_path):
    col, m = _col(tmp_path)
    broken = _add(col, m, furi="難[なん]を逃[のが]れる")
    clean = _add(col, m, furi="部活[ぶかつ]")
    plan = furigana.build_plan(col)
    by = {(f.note_id, f.field): f for f in plan}
    assert by[(broken, config.TERM_FURIGANA)].new == "難[なん]を 逃[のが]れる"
    assert (clean, config.TERM_FURIGANA) not in by   # already correct -> skipped
    col.close()


def test_plan_covers_sentence_furigana(tmp_path):
    col, m = _col(tmp_path)
    nid = _add(col, m, sfuri="引[ひ]けを取[と]らない")
    plan = furigana.build_plan(col)
    by = {(f.note_id, f.field): f for f in plan}
    assert by[(nid, config.SENTENCE_FURIGANA)].new == "引[ひ]けを 取[と]らない"
    col.close()


def test_apply_writes_through_col(tmp_path):
    col, m = _col(tmp_path)
    nid = _add(col, m, furi="難[なん]を逃[のが]れる", sfuri="お忍[しの]び")
    n = furigana.apply_plan(col, furigana.build_plan(col))
    assert n == 2                                    # both fields counted
    note = col.get_note(nid)
    assert note[config.TERM_FURIGANA] == "難[なん]を 逃[のが]れる"
    assert note[config.SENTENCE_FURIGANA] == "お 忍[しの]び"
    assert furigana.build_plan(col) == []            # idempotent
    col.close()
