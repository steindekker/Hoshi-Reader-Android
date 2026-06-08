import pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankialign import config, query_links

KINOMOTO_FIELDS = ["Term", "Reading", "Furigana", "Sentence", "SentenceFurigana",
    "Cloze Before", "Cloze Inside", "Cloze After", "Meaning", "Expanded Meaning",
    "Collapsed Meaning", "Notes", "Context", "Frequency", "Pitch Accent", "Image",
    "Term Audio", "Sentence Audio"]


def _kinomoto(col):
    m = col.models.new(config.TARGET_NOTETYPE)
    for fn in KINOMOTO_FIELDS:
        col.models.add_field(m, col.models.new_field(fn))
    t = col.models.new_template("Card 1")
    t["qfmt"] = "{{Term}}"; t["afmt"] = "{{Meaning}}"
    col.models.add_template(m, t)
    col.models.add(m)
    return col.models.by_name(config.TARGET_NOTETYPE)


REL = '<a href="?query=猫&wildcards=off">猫</a>'
ABS = '<a href="https://maps.apple.com/?q=x">所</a>'


@pytest.fixture
def col(tmp_path):
    c = Collection(str(tmp_path / "t.anki2"))
    m = _kinomoto(c); c.models.set_current(m)
    deck = c.decks.id("言葉文章")

    def add(meaning):
        n = c.new_note(m); n["Term"] = "x"; n["Meaning"] = meaning
        c.add_note(n, deck); return n.id

    add(f"{REL} は動物。")       # relative -> unwrap
    add(f"{ABS} にある。")       # absolute -> keep
    add("ただのテキスト。")      # nothing to do
    yield c
    c.close()


def test_fix_field_unwraps_relative_keeps_absolute():
    assert query_links.fix_field(REL) == "猫"
    assert query_links.fix_field(ABS) == ABS


def test_run_dry_counts_without_writing(col):
    res = query_links.run(col, apply=False)
    assert res == {"changed": 1, "anchors": 1}
    nids = list(col.find_notes(f'note:"{config.TARGET_NOTETYPE}"'))
    assert any(REL in col.get_note(i)["Meaning"] for i in nids)   # unchanged


def test_run_apply_unwraps_and_is_idempotent(col):
    assert query_links.run(col, apply=True) == {"changed": 1, "anchors": 1}
    nids = list(col.find_notes(f'note:"{config.TARGET_NOTETYPE}"'))
    meanings = [col.get_note(i)["Meaning"] for i in nids]
    assert any(m.startswith("猫 は動物。") for m in meanings)     # unwrapped
    assert any(ABS in m for m in meanings)                        # kept
    assert query_links.run(col, apply=True) == {"changed": 0, "anchors": 0}
