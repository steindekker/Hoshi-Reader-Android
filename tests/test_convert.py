import pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankialign import convert, config


def _new_notetype(col, name, fields):
    m = col.models.new(name)
    for fname in fields:
        col.models.add_field(m, col.models.new_field(fname))
    t = col.models.new_template("Card 1")
    t["qfmt"] = "{{%s}}" % fields[0]
    t["afmt"] = "{{FrontSide}}<hr>{{%s}}" % fields[1] if len(fields) > 1 else "{{FrontSide}}"
    col.models.add_template(m, t)
    col.models.add(m)
    return col.models.by_name(name)


KINOMOTO_FIELDS = ["Term", "Reading", "Furigana", "Sentence", "SentenceFurigana",
    "Cloze Before", "Cloze Inside", "Cloze After", "Meaning", "Expanded Meaning",
    "Collapsed Meaning", "Notes", "Context", "Frequency", "Pitch Accent", "Image",
    "Term Audio", "Sentence Audio"]
MYNOTE_FIELDS = ["Sentence", "Translation", "Target Word", "Definitions", "Screenshot",
    "Sentence Audio", "Word Audio", "Images", "Example Sentences", "Frequency", "Pitch"]


@pytest.fixture
def col(tmp_path):
    c = Collection(str(tmp_path / "test.anki2"))
    _new_notetype(c, config.TARGET_NOTETYPE, KINOMOTO_FIELDS)
    _new_notetype(c, "MyNote", MYNOTE_FIELDS)
    mined = c.decks.id("言葉文章")
    core = c.decks.id("言葉Core2.3k")
    mynote = c.models.by_name("MyNote")
    c.models.set_current(mynote)
    # in-scope note
    n1 = c.new_note(mynote)
    n1["Target Word"] = "{[一|ひと]つ;2}"; n1["Sentence"] = "一つください。"
    n1["Definitions"] = "one"; n1["Word Audio"] = "[sound:a.mp3]"
    c.add_note(n1, mined)
    # excluded (Core) note
    n2 = c.new_note(mynote)
    n2["Target Word"] = "二"; n2["Sentence"] = "二です。"
    c.add_note(n2, core)
    yield c
    c.close()


def test_select_notes_excludes_core(col):
    sel = convert.select_notes(col)
    assert len(sel["MyNote"]) == 1   # the Core note is excluded


def test_build_plan_is_readonly_and_maps(col):
    before = col.models.by_name("MyNote")["id"]
    plan = convert.build_plan(col)
    # nothing mutated
    n = col.get_note(convert.select_notes(col)["MyNote"][0])
    assert n.note_type()["id"] == before
    # plan content
    assert plan["sources"]["MyNote"]["count"] == 1
    src, kino = plan["sources"]["MyNote"]["samples"][0]
    assert kino["Term"] == "一つ" and kino["Reading"] == "ひとつ"
    assert plan["errors"] == []


def test_run_conversion_preserves_schedule_and_maps(col, tmp_path):
    nid = convert.select_notes(col)["MyNote"][0]
    card = col.get_card(col.card_ids_of_note(nid)[0])
    # give the card a non-default schedule
    card.ivl = 42; card.reps = 7; card.due = 12345; card.type = 2
    col.update_card(card)
    cid = card.id

    result = convert.run_conversion(col, backup_dir=str(tmp_path / "bk"))

    note = col.get_note(nid)
    assert note.note_type()["name"] == config.TARGET_NOTETYPE
    assert note["Term"] == "一つ" and note["Reading"] == "ひとつ"
    assert note["Furigana"] == "一[ひと]つ"
    assert note["Meaning"] == "one" and note["Term Audio"] == "[sound:a.mp3]"
    # same card, schedule intact
    c2 = col.get_card(cid)
    assert (c2.ivl, c2.reps, c2.due, c2.type) == (42, 7, 12345, 2)
    assert result["converted"]["MyNote"] == 1


def test_run_conversion_aborts_without_target(tmp_path):
    from anki.collection import Collection
    c = Collection(str(tmp_path / "empty.anki2"))
    try:
        with pytest.raises(RuntimeError, match="target notetype"):
            convert.run_conversion(c, backup_dir=str(tmp_path / "bk"))
    finally:
        c.close()
