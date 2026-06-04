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
