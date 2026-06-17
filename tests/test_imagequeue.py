import pytest

from ankienrich import config

anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankienrich import imagequeue

KINOMOTO_FIELDS = [
    config.TERM, config.READING, config.SENTENCE, config.DEFINITION, config.IMAGE,
]


def _new_notetype(col, name, fields):
    m = col.models.new(name)
    for fname in fields:
        col.models.add_field(m, col.models.new_field(fname))
    t = col.models.new_template("Card 1")
    t["qfmt"], t["afmt"] = "{{%s}}" % fields[0], "{{FrontSide}}"
    col.models.add_template(m, t)
    col.models.add(m)
    return col.models.by_name(name)


@pytest.fixture
def kcol(tmp_path):
    c = Collection(str(tmp_path / "k.anki2"))
    _new_notetype(c, config.NOTETYPE, KINOMOTO_FIELDS)
    yield c
    c.close()


def _add(col, term, reading, *, image="", sentence="", meaning="", tags=()):
    m = col.models.by_name(config.NOTETYPE)
    col.models.set_current(m)
    n = col.new_note(m)
    n[config.TERM] = term
    n[config.READING] = reading
    n[config.IMAGE] = image
    n[config.SENTENCE] = sentence
    n[config.DEFINITION] = meaning
    for t in tags:
        n.add_tag(t)
    col.add_note(n, col.decks.id("言葉文章"))
    return n.id


def test_image_queue_constants_exist():
    assert config.NEEDS_IMAGE_TAG == "ankienrich::needs-image"
    assert config.NO_IMAGE_TAG == "ankienrich::no-image"
    assert config.IMAGE_BUTTON == "🖼 画像"


def test_flag_new_tags_only_imageless_unflagged(kcol):
    a = _add(kcol, "迷惑", "めいわく")                                   # flag
    b = _add(kcol, "箸", "はし", image='<img src="x.jpg">')             # has image: skip
    c = _add(kcol, "橋", "はし", tags=[config.NO_IMAGE_TAG])             # already excluded
    d = _add(kcol, "端", "はし", tags=[config.NEEDS_IMAGE_TAG])         # already flagged
    n = imagequeue.flag_new(kcol, [a, b, c, d])
    assert n == 1
    assert config.NEEDS_IMAGE_TAG in kcol.get_note(a).tags
    assert config.NEEDS_IMAGE_TAG not in kcol.get_note(b).tags
    assert config.NEEDS_IMAGE_TAG not in kcol.get_note(c).tags


def test_candidates_returns_flagged_in_nid_order_with_context(kcol):
    a = _add(kcol, "迷惑", "めいわく", sentence="迷惑をかける", tags=[config.NEEDS_IMAGE_TAG])
    _add(kcol, "箸", "はし")  # untagged: excluded
    b = _add(kcol, "橋", "はし", meaning="a bridge", tags=[config.NEEDS_IMAGE_TAG])
    out = imagequeue.candidates(kcol)
    assert [c.nid for c in out] == sorted([a, b])
    by_nid = {c.nid: c for c in out}
    assert by_nid[a].term == "迷惑" and by_nid[a].reading == "めいわく"
    assert "迷惑をかける" in by_nid[a].context        # from Sentence
    assert "a bridge" in by_nid[b].context           # falls back to Meaning
