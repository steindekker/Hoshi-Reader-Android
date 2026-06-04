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
YUUNA_FIELDS = ["Sentence", "Term", "Reading", "Meaning", "Notes", "Image",
    "Term Audio", "Pitch Accent", "Furigana", "Expanded Meaning",
    "Collapsed Meaning", "Sentence Audio"]
KANJISTUDY_FIELDS = ["ID", "Entry", "EntryFurigana", "Meaning", "Sentence",
    "Translation", "Screenshot", "Word Audio", "Sentence Audio", "Images",
    "Example Sentences", "Frequency"]


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


def test_run_conversion_multi_source_and_leaves_excluded(col, tmp_path):
    mined = col.decks.id("言葉文章")
    yuuna = _new_notetype(col, "jidoujisho Yuuna", YUUNA_FIELDS)
    ks = _new_notetype(col, "Kanji Study Word Model v3", KANJISTUDY_FIELDS)
    ny = col.new_note(yuuna)
    ny["Term"] = "{[土砂|どしゃ];1}"; ny["Reading"] = "どしゃ"
    ny["Furigana"] = "土砂[どしゃ]"; ny["Meaning"] = "earth and sand"
    col.add_note(ny, mined)
    nk = col.new_note(ks)
    nk["EntryFurigana"] = "有[あ]りそう"; nk["Meaning"] = "probable"
    nk["Word Audio"] = "[sound:k.mp3]"
    col.add_note(nk, mined)
    core_nid = col.find_notes('note:MyNote deck:*Core2.3k*')[0]

    result = convert.run_conversion(col, backup_dir=str(tmp_path / "bk"))

    assert result["converted"] == {
        "MyNote": 1, "jidoujisho Yuuna": 1, "Kanji Study Word Model v3": 1}
    # excluded Core note untouched
    assert col.get_note(core_nid).note_type()["name"] == "MyNote"
    # Yuuna + KanjiStudy mapped onto Kinomoto
    yn = col.get_note(ny.id)
    assert yn.note_type()["name"] == config.TARGET_NOTETYPE
    assert yn["Term"] == "土砂" and yn["Reading"] == "どしゃ"
    kn = col.get_note(nk.id)
    assert kn["Term"] == "有りそう" and kn["Reading"] == "ありそう"


def test_run_conversion_aborts_without_target(tmp_path):
    from anki.collection import Collection
    c = Collection(str(tmp_path / "empty.anki2"))
    try:
        with pytest.raises(RuntimeError, match="target notetype"):
            convert.run_conversion(c, backup_dir=str(tmp_path / "bk"))
    finally:
        c.close()


def test_normalize_media_wraps_bare_and_is_idempotent(col):
    kino = col.models.by_name(config.TARGET_NOTETYPE)
    col.models.set_current(kino)
    n = col.new_note(kino)
    n["Term"] = "テスト"
    n["Term Audio"] = "abc.mp3"            # bare -> should wrap
    n["Image"] = "pic.jpg"                 # bare -> should wrap
    n["Sentence Audio"] = "[sound:s.mp3]"  # already wrapped -> untouched
    col.add_note(n, col.decks.id("言葉文章"))

    assert convert.normalize_media(col) == 1
    n2 = col.get_note(n.id)
    assert n2["Term Audio"] == "[sound:abc.mp3]"
    assert n2["Image"] == '<img src="pic.jpg">'
    assert n2["Sentence Audio"] == "[sound:s.mp3]"
    # idempotent: a second pass changes nothing
    assert convert.normalize_media(col) == 0
