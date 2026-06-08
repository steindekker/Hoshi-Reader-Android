# tests/test_pitch_engine.py
import gzip, marshal
import pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankialign import pitch, config

KINOMOTO_FIELDS = ["Term", "Reading", "Furigana", "Sentence", "SentenceFurigana",
    "Cloze Before", "Cloze Inside", "Cloze After", "Meaning", "Expanded Meaning",
    "Collapsed Meaning", "Notes", "Context", "Frequency", "Pitch Accent", "Image",
    "Term Audio", "Sentence Audio"]


def _kinomoto(col):
    m = col.models.new(config.TARGET_NOTETYPE)
    for fn in KINOMOTO_FIELDS:
        col.models.add_field(m, col.models.new_field(fn))
    t = col.models.new_template("Card 1")
    t["qfmt"] = "{{Term}}"; t["afmt"] = "{{FrontSide}}<hr>{{Reading}}"
    col.models.add_template(m, t)
    col.models.add(m)
    return col.models.by_name(config.TARGET_NOTETYPE)


@pytest.fixture
def col(tmp_path, monkeypatch):
    c = Collection(str(tmp_path / "t.anki2"))
    m = _kinomoto(c)
    c.models.set_current(m)
    deck = c.decks.id("言葉文章")

    def add(term, reading, pa=""):
        n = c.new_note(m)
        n["Term"] = term; n["Reading"] = reading; n["Pitch Accent"] = pa
        c.add_note(n, deck); return n.id

    add("異変", "いへん")               # fillable (single accent)
    add("加味", "かみ")                 # fillable (single accent)
    add("裕福", "ゆうふく")             # fillable, multi-accent -> stacked graphs
    add("既存", "きそん", pa="<svg>existing</svg>")  # already has a graph -> skip
    add("謎語", "なぞご")               # not in index -> not_found
    add("無読", "")                     # no reading -> skipped

    # tiny AccentIndex file + point the engine at it (values are preference-ordered
    # lists; multi-accent words render one stacked graph per accent)
    idxfile = tmp_path / "accent_index.marshal.gz"
    with gzip.open(idxfile, "wb") as f:
        f.write(marshal.dumps({"異変\tいへん": [0], "加味\tかみ": [1],
                               "裕福\tゆうふく": [0, 1]}))
    monkeypatch.setattr(pitch, "_INDEX_PATH", str(idxfile))
    yield c
    c.close()


def test_build_pitch_plan_counts(col):
    plan = pitch.build_pitch_plan(col)
    assert plan["counts"] == {"filled": 3, "not_found": 1, "skipped": 1}
    assert plan["counts"]["filled"] == len(plan["samples"]) or len(plan["samples"]) <= config.SAMPLE_N


def test_backfill_fills_only_empty(col):
    res = pitch.backfill_pitch(col)
    assert res == {"filled": 3, "not_found": 1, "skipped": 1}
    nids = list(col.find_notes(f'note:"{config.TARGET_NOTETYPE}"'))
    pas = {col.get_note(i)["Term"]: col.get_note(i)["Pitch Accent"] for i in nids}
    assert "<svg" in pas["異変"] and "<svg" in pas["加味"]
    # multi-accent word stacks one graph per accent, <br>-separated
    assert pas["裕福"].count("<svg") == 2 and "<br>" in pas["裕福"]
    assert pas["既存"] == "<svg>existing</svg>"   # untouched
    assert pas["謎語"] == "" and pas["無読"] == ""


def test_backfill_is_idempotent(col):
    pitch.backfill_pitch(col)
    res2 = pitch.backfill_pitch(col)
    assert res2["filled"] == 0


def test_overwrite_rerenders_stale_only(col):
    pitch.backfill_pitch(col)                                  # fill all empties
    assert pitch.backfill_pitch(col)["filled"] == 0           # idempotent

    nid = col.find_notes('"Term:異変"')[0]                     # in-dict, give a stale graph
    n = col.get_note(nid); n["Pitch Accent"] = "<svg>STALE</svg>"; col.update_note(n)

    # default leaves the existing (stale) graph alone
    assert pitch.backfill_pitch(col)["filled"] == 0
    assert "STALE" in col.get_note(nid)["Pitch Accent"]

    # overwrite re-renders the stale one (and only it — the rest already match)
    assert pitch.backfill_pitch(col, overwrite=True)["filled"] == 1
    pa = col.get_note(nid)["Pitch Accent"]
    assert "STALE" not in pa and "<svg" in pa

    # a note the dicts can't cover keeps its existing graph (never cleared)
    ek = col.find_notes('"Term:既存"')[0]
    assert col.get_note(ek)["Pitch Accent"] == "<svg>existing</svg>"
