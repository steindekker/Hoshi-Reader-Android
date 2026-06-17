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


import contextlib


def _stub_sync(monkeypatch, col):
    """Point imagequeue's sync envelope at an open temp collection without network
    or runlock, and keep the handle open so the test can assert on it afterward."""
    monkeypatch.setattr(imagequeue.runlock, "acquire", lambda: contextlib.nullcontext())
    monkeypatch.setattr(imagequeue.collection, "open_working", lambda: col)
    monkeypatch.setattr(imagequeue.sync, "login", lambda c: "auth")
    monkeypatch.setattr(imagequeue.sync, "pull", lambda c, a: ("none", a))
    monkeypatch.setattr(imagequeue.sync, "push", lambda c, a: ("ok", a))
    monkeypatch.setattr(col, "close", lambda: None)   # set_image/skip close in finally


def test_set_image_writes_field_media_and_clears_tag(kcol, monkeypatch):
    nid = _add(kcol, "迷惑", "めいわく", tags=[config.NEEDS_IMAGE_TAG])
    _stub_sync(monkeypatch, kcol)
    res = imagequeue.set_image(nid, (b"\x89PNGfake", "png"))
    assert res["status"] == "ok"
    note = kcol.get_note(nid)
    assert note[config.IMAGE] == f'<img src="q_{nid}.png">'
    assert config.NEEDS_IMAGE_TAG not in note.tags
    assert kcol.media.have(f"q_{nid}.png")


def test_set_image_gone_when_nid_absent(kcol, monkeypatch):
    _stub_sync(monkeypatch, kcol)
    res = imagequeue.set_image(99999999, (b"x", "jpg"))
    assert res["status"] == "gone"


def test_skip_swaps_needs_image_for_no_image(kcol, monkeypatch):
    nid = _add(kcol, "迷惑", "めいわく", tags=[config.NEEDS_IMAGE_TAG])
    _stub_sync(monkeypatch, kcol)
    res = imagequeue.skip(nid)
    assert res["status"] == "ok"
    note = kcol.get_note(nid)
    assert config.NEEDS_IMAGE_TAG not in note.tags
    assert config.NO_IMAGE_TAG in note.tags


def test_queue_image_rows_numbered_then_skip_then_end():
    rows = imagequeue.queue_image_rows([object(), object()])
    assert rows[0] == ("1", "qpick:0")
    assert rows[1] == ("2", "qpick:1")
    assert rows[-2] == ("⏭ スキップ", "qskip")
    assert rows[-1] == ("■ 終了", "qend")


def test_queue_session_transitions_and_summary():
    c = imagequeue.Candidate(1, "a", "あ", "ctx")
    d = imagequeue.Candidate(2, "b", "い", "ctx")
    q = imagequeue.QueueSession(cards=[c, d])
    assert not q.done and q.current is c
    q.record_image()                      # imaged the first card
    assert q.current is d and q.imaged == 1
    q.record_skip()                       # skipped the second
    assert q.done and q.skipped == 1
    assert q.current is None
    assert imagequeue.summary_text(q) == "完了（imaged 1・skipped 1）"


def test_queue_session_advance_does_not_count():
    q = imagequeue.QueueSession(cards=[imagequeue.Candidate(1, "a", "あ", "")])
    q.advance()                           # auto-skip (browser down): index only
    assert q.done and q.imaged == 0 and q.skipped == 0
