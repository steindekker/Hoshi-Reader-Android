import os, pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankienrich.jrp_fill import engine

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

def test_make_generator_marks_a_plain_sentence():
    # the real (Sudachi + accent index) generator used by `anki-enrich`
    from ankienrich.jrp_fill import make_generator
    gen = make_generator()
    out = gen("猫が歩いた")
    from ankienrich import markup
    assert ("{" in out or "[" in out)              # produced JRP markup
    assert markup.jrp_surface(out) == "猫が歩いた"   # round-trips to the input


def test_apply_to_fixpoint_converges_multipass(tmp_path):
    # generate that marks one kanji per pass, leaving the rest plain — so a single
    # build_plan/apply leaves residue (the real generator does this when it emits a
    # bare kanji that splice then re-fills next pass).
    col, m = _make_col(tmp_path)
    nid = _add(col, m, "日本", "日本")
    gen = lambda t: {"日本": "{日;1}本", "本": "{本;1}", "日": "{日;1}"}.get(t, t)
    # one pass leaves residue
    assert len(engine.build_plan(col, "jidoujisho Kinomoto", "Sentence", gen)) == 1
    # fixpoint apply drives it all the way
    total = engine.apply_to_fixpoint(col, "jidoujisho Kinomoto", "Sentence", gen)
    assert total == 2
    assert engine.build_plan(col, "jidoujisho Kinomoto", "Sentence", gen) == []
    assert col.get_note(nid)["Sentence"] == "{日;1}{本;1}"
    col.close()


def test_regenerate_not_undone_by_splice_repitch(tmp_path):
    # A regenerate pass may DEPITCH a token (render it bare) — e.g. a suffix the
    # current generator no longer pitches. The follow-up splice pass must not treat
    # that bared token as an empty gap and re-pitch it, reverting the rebuild.
    col, m = _make_col(tmp_path)
    nid = _add(col, m, "猫達", "{猫;1}{達;1}")          # stale: suffix 達 wrongly pitched
    # context-dependent: depitches 達 in the whole sentence, but pitches it alone
    gen = lambda t: {"猫達": "{猫;1}達", "達": "{達;1}", "猫": "{猫;1}"}.get(t, t)
    engine.apply_to_fixpoint(col, "jidoujisho Kinomoto", "Sentence", gen, regenerate=True)
    assert col.get_note(nid)["Sentence"] == "{猫;1}達"   # depitch persists, not reverted
    assert engine.build_plan(col, "jidoujisho Kinomoto", "Sentence",
                             gen, regenerate=True) == []
    col.close()


def test_plan_strips_html_from_sentence(tmp_path):
    col, m = _make_col(tmp_path)
    # bold wrapper + a <br>: tags must be stripped (br -> newline), JRP kept
    nid = _add(col, m, "x", "<b>{[旅路|たびじ];0,2}</b>を<br>進む")
    plan = engine.build_plan(col, "jidoujisho Kinomoto", "Sentence",
                             generate=lambda t: "{" + t + ";0}")
    by_id = {p.note_id: p for p in plan}
    assert by_id[nid].new_value == "{[旅路|たびじ];0,2}を\n{進む;0}"
    col.close()


def test_plan_normalizes_fullwidth_space(tmp_path):
    col, m = _make_col(tmp_path)
    # ideographic spaces in a freshly-mined sentence -> ASCII space in the fill
    nid = _add(col, m, "話", "今は　そげな話は　あとじゃ。")
    plan = engine.build_plan(col, "jidoujisho Kinomoto", "Sentence",
                             generate=lambda t: t)   # identity: surface passes through
    by_id = {p.note_id: p for p in plan}
    assert by_id[nid].new_value == "今は そげな話は あとじゃ。"
    col.close()


def test_plan_html_only_cleanup_still_writes(tmp_path):
    col, m = _make_col(tmp_path)
    # fully-marked sentence wrapped in <b>: no JRP work, but the tags must go
    nid = _add(col, m, "x", "<b>{[異変|いへん];0}だ</b>")
    plan = engine.build_plan(col, "jidoujisho Kinomoto", "Sentence",
                             generate=lambda t: "{" + t + ";0}")
    by_id = {p.note_id: p for p in plan}
    assert by_id[nid].new_value == "{[異変|いへん];0}だ"
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
