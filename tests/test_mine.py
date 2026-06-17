import pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankienrich import config, mine, enrich_one
from ankienrich.audio.enrich import Pick

KINOMOTO_FIELDS = [
    "Term", "Reading", "Furigana", "Sentence", "SentenceFurigana",
    "Cloze Before", "Cloze Inside", "Cloze After", "Meaning",
    "Expanded Meaning", "Collapsed Meaning", "Notes", "Context", "Frequency",
    "Pitch Accent", "Image", "Term Audio", "Sentence Audio",
    "Original Meaning", "Definition Source", "Examples",
]


def _new_kinomoto(col):
    m = col.models.new(config.NOTETYPE)
    for fname in KINOMOTO_FIELDS:
        col.models.add_field(m, col.models.new_field(fname))
    t = col.models.new_template("c")
    t["qfmt"] = "{{Term}}"
    t["afmt"] = "{{Meaning}}"
    col.models.add_template(m, t)
    col.models.add(m)
    return col.models.by_name(config.NOTETYPE)


def _col(tmp_path):
    c = Collection(str(tmp_path / "k.anki2"))
    _new_kinomoto(c)
    return c


def _add(col, term, reading, deck):
    m = col.models.by_name(config.NOTETYPE)
    n = col.new_note(m)
    n["Term"] = term
    n["Reading"] = reading
    col.add_note(n, col.decks.id(deck))
    return n


def test_target_deck_is_modal_deck(tmp_path):
    col = _col(tmp_path)
    _add(col, "A", "a", "言葉文章")
    _add(col, "B", "b", "言葉文章")
    _add(col, "C", "c", "Other")
    assert mine.target_deck(col) == "言葉文章"
    col.close()


def test_existing_note_matches_term_and_reading(tmp_path):
    col = _col(tmp_path)
    n = _add(col, "箸", "はし", "言葉文章")
    assert mine.existing_note(col, "箸", "はし") == n.id
    assert mine.existing_note(col, "箸", "ちょう") is None
    assert mine.existing_note(col, "未知", "みち") is None
    assert mine.existing_note(col, 'bad"quote', "x") is None  # no SearchError
    col.close()


def test_create_card_writes_fields_media_deck_tags(tmp_path):
    col = _col(tmp_path)
    fields = enrich_one.Fields(
        term="箸", reading="はし", furigana="箸[はし]", pitch_svg="<svg/>",
        meaning="chopsticks", definition_source="JMdict", examples="<div>例</div>",
        audio_pick=Pick("nhk16", "1.opus"), audio_clip=b"OPUS")
    sf = enrich_one.SentenceFields("毎日{箸}", "毎日 箸[はし]", "毎日", "箸", "を使う")
    nid = mine.create_card(col, fields, sf, deck="言葉文章",
                           tags=[config.MINED_TAG, config.ENRICHED_TAG])
    n = col.get_note(nid)
    assert n["Term"] == "箸" and n["Furigana"] == "箸[はし]"
    assert n["Meaning"] == "chopsticks" and n["Definition Source"] == "JMdict"
    assert n["Examples"] == "<div>例</div>"
    assert n["Pitch Accent"] == "<svg/>"
    assert n["Sentence"] == "毎日{箸}" and n["Cloze Inside"] == "箸"
    assert n["Term Audio"].startswith("[sound:") and n["Term Audio"].endswith("]")
    assert n["Image"] == ""    # no image supplied
    assert config.MINED_TAG in n.tags and config.ENRICHED_TAG in n.tags
    cid = col.card_ids_of_note(nid)[0]
    assert col.decks.name(col.get_card(cid).did) == "言葉文章"
    col.close()


def test_create_card_without_sentence_leaves_those_fields_empty(tmp_path):
    col = _col(tmp_path)
    fields = enrich_one.Fields("箸", "はし", "箸[はし]", "", "chopsticks", "JMdict", "", None, None)
    nid = mine.create_card(col, fields, None, deck="言葉文章", tags=[config.MINED_TAG])
    n = col.get_note(nid)
    assert n["Sentence"] == "" and n["Cloze Inside"] == "" and n["Term Audio"] == ""
    assert n["Image"] == ""
    col.close()


def test_create_card_with_image_writes_field_and_media(tmp_path):
    col = _col(tmp_path)
    fields = enrich_one.Fields("箸", "はし", "箸[はし]", "", "chopsticks", "JMdict", "", None, None)
    nid = mine.create_card(col, fields, None, deck="言葉文章",
                           tags=[config.MINED_TAG], image=(b"JPEGDATA", "jpg"))
    n = col.get_note(nid)
    assert n["Image"].startswith('<img src="') and n["Image"].endswith('">')
    assert "mined_箸" in n["Image"]
    col.close()


def test_card_tags_with_and_without_examples():
    f = enrich_one.Fields("a", "a", "a", "", "m", "s", "<div/>", None, None)
    assert config.EXAMPLES_TAG in mine.card_tags(f)
    f2 = enrich_one.Fields("a", "a", "a", "", "m", "s", "", None, None)
    assert config.NO_EXAMPLE_TAG in mine.card_tags(f2)


def test_sentence_options_uses_massif(monkeypatch):
    monkeypatch.setattr(mine.massif, "top_sentences", lambda term, n=5: ["S1", "S2"])
    assert mine.sentence_options("箸", ex_conn=object()) == ["S1", "S2"]

def test_sentence_options_falls_back_to_local(monkeypatch):
    monkeypatch.setattr(mine.massif, "top_sentences", lambda term, n=5: [])
    monkeypatch.setattr(mine, "_local_sentences", lambda conn, term, n: ["L1"])
    assert mine.sentence_options("箸", ex_conn=object()) == ["L1"]

def test_image_candidates_caps_and_swallows_errors(monkeypatch):
    from ankienrich.images import ImageCandidate
    monkeypatch.setattr(mine.images, "search_images",
                        lambda term, limit=6: [ImageCandidate("t","f","","")])
    assert len(mine.image_candidates("箸")) == 1
    def _boom(*a, **k): raise RuntimeError("no browser")
    monkeypatch.setattr(mine.images, "search_images", _boom)
    assert mine.image_candidates("箸") == []

def test_download_image_returns_bytes_and_ext(monkeypatch):
    class _R:
        status_code = 200
        headers = {"Content-Type": "image/jpeg"}
        def raise_for_status(self): pass
        def iter_content(self, n): return iter([b"JP", b"EG"])
    monkeypatch.setattr(mine.requests, "get", lambda u, **k: _R())
    assert mine.download_image("http://x/y/pic.png") == (b"JPEG", "png")

def test_download_image_none_when_oversize(monkeypatch):
    big = b"x" * (mine._MAX_IMAGE_BYTES + 1)
    class _R:
        headers = {"Content-Type": "image/jpeg"}
        def raise_for_status(self): pass
        def iter_content(self, n): return iter([big])
    monkeypatch.setattr(mine.requests, "get", lambda u, **k: _R())
    assert mine.download_image("http://x/pic.jpg") is None

def test_download_image_none_on_failure(monkeypatch):
    def _boom(*a, **k): raise mine.requests.RequestException("down")
    monkeypatch.setattr(mine.requests, "get", _boom)
    assert mine.download_image("http://x/pic.jpg") is None

def test_download_image_none_on_non_image(monkeypatch):
    class _R:
        headers = {"Content-Type": "text/html"}
        def raise_for_status(self): pass
    monkeypatch.setattr(mine.requests, "get", lambda u, **k: _R())
    assert mine.download_image("http://x/pic.jpg") is None

def test_commit_refuses_duplicate(monkeypatch):
    class _Col:
        def close(self): pass
    monkeypatch.setattr(mine.collection, "open_working", lambda: _Col())
    monkeypatch.setattr(mine.sync, "login", lambda col: "auth")
    monkeypatch.setattr(mine.sync, "pull", lambda col, a: ("none", a))
    monkeypatch.setattr(mine, "existing_note", lambda col, t, r: 123)
    f = enrich_one.Fields("箸","はし","箸[はし]","","m","s","",None,None)
    res = mine.commit(f, None)
    assert res["status"] == "duplicate"


def test_candidate_rows_label_and_payload():
    from ankienrich.enrich_one import Candidate
    rows = mine.candidate_rows([Candidate("かど", "corner"), Candidate("つの", "horn")])
    assert rows[0] == ("かど — corner", "cand:0")
    assert rows[1] == ("つの — horn", "cand:1")


def test_sentence_rows_number_and_payload():
    rows = mine.sentence_rows(["AAA", "BBB"])
    assert rows[0][1] == "sent:0" and "AAA" in rows[0][0]
    assert rows[-1] == ("⏭ Skip sentence", "sent:skip")


def test_image_rows_number_and_skip():
    from ankienrich.images import ImageCandidate
    cands = [ImageCandidate("t1","f1","",""), ImageCandidate("t2","f2","","")]
    rows = mine.image_rows(cands)
    assert rows[0] == ("1", "img:0") and rows[-1] == ("⏭ Skip image", "img:skip")


def test_preview_text_includes_reading_meaning():
    from ankienrich.enrich_one import Fields
    f = Fields("箸","はし","箸[はし]","<svg/>","chopsticks","JMdict","",None,b"x")
    txt = mine.preview_text(f)
    assert "はし" in txt and "chopsticks" in txt and "JMdict" in txt


def test_confirm_text_image_flag():
    from ankienrich.enrich_one import Fields
    f = Fields("箸","はし","箸[はし]","","chopsticks","JMdict","",None,None)
    assert "yes" in mine.confirm_text(f, None, has_image=True)
    assert "—" in mine.confirm_text(f, None, has_image=False)


def test_session_defaults():
    s = mine.Session(chat_id=1)
    assert s.state == "idle" and s.candidates == [] and s.fields is None
    assert s.image_candidates == [] and s.image is None
