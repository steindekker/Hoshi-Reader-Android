import gzip
import marshal

import pytest

anki = pytest.importorskip("anki")
from anki.collection import Collection

from ankienrich import config, pitch


def _kinomoto(col):
    m = col.models.new(config.NOTETYPE)
    for fn in (config.TERM, config.READING, config.TERM_FURIGANA, config.SENTENCE,
               config.SENTENCE_FURIGANA, config.CLOZE_BEFORE, config.CLOZE_INSIDE,
               config.CLOZE_AFTER, config.DEFINITION, config.PITCH, config.TERM_AUDIO):
        col.models.add_field(m, col.models.new_field(fn))
    t = col.models.new_template("Card 1")
    t["qfmt"] = "{{%s}}" % config.TERM
    t["afmt"] = "{{FrontSide}}<hr>{{%s}}" % config.READING
    col.models.add_template(m, t)
    col.models.add(m)
    return col.models.by_name(config.NOTETYPE)


@pytest.fixture
def col(tmp_path, monkeypatch):
    c = Collection(str(tmp_path / "t.anki2"))
    m = _kinomoto(c)
    c.models.set_current(m)
    deck = c.decks.id("言葉文章")

    def add(term, reading, pa=""):
        n = c.new_note(m)
        n[config.TERM] = term
        n[config.READING] = reading
        n[config.PITCH] = pa
        c.add_note(n, deck)
        return n.id

    add("異変", "いへん")               # fillable (single accent)
    add("加味", "かみ")                 # fillable (single accent)
    add("裕福", "ゆうふく")             # fillable, multi-accent -> stacked graphs
    add("既存", "きそん", pa="<svg>existing</svg>")  # already has a graph -> skip
    add("謎語", "なぞご")               # not in index -> not_found
    add("無読", "")                     # no reading -> skipped

    # tiny AccentIndex file; point the engine at it
    idxfile = tmp_path / "accent_index.marshal.gz"
    with gzip.open(idxfile, "wb") as f:
        f.write(marshal.dumps({"異変\tいへん": [0], "加味\tかみ": [1],
                               "裕福\tゆうふく": [0, 1]}))
    monkeypatch.setattr(pitch, "_INDEX_PATH", str(idxfile))
    yield c
    c.close()


def test_plan_backfill_counts(col):
    plan = pitch.plan_backfill(col)
    assert plan["counts"] == {"filled": 3, "not_found": 1, "skipped": 1}
    assert len(plan["samples"]) == plan["counts"]["filled"] or len(plan["samples"]) <= config.SAMPLE_N


def test_backfill_fills_only_empty(col):
    res = pitch.run_backfill(col)
    assert res == {"filled": 3, "not_found": 1, "skipped": 1}
    nids = list(col.find_notes(f'note:"{config.NOTETYPE}"'))
    pas = {col.get_note(i)[config.TERM]: col.get_note(i)[config.PITCH] for i in nids}
    assert "<svg" in pas["異変"] and "<svg" in pas["加味"]
    # multi-accent word stacks one graph per accent, <br>-separated
    assert pas["裕福"].count("<svg") == 2 and "<br>" in pas["裕福"]
    assert pas["既存"] == "<svg>existing</svg>"   # untouched
    assert pas["謎語"] == "" and pas["無読"] == ""


def test_backfill_is_idempotent(col):
    pitch.run_backfill(col)
    res2 = pitch.run_backfill(col)
    assert res2["filled"] == 0


def test_overwrite_rerenders_stale_only(col):
    pitch.run_backfill(col)                                  # fill all empties
    assert pitch.run_backfill(col)["filled"] == 0           # idempotent

    nid = col.find_notes('"Term:異変"')[0]                     # in-dict, give a stale graph
    n = col.get_note(nid)
    n[config.PITCH] = "<svg>STALE</svg>"
    col.update_note(n)

    # default leaves the existing (stale) graph alone
    assert pitch.run_backfill(col)["filled"] == 0
    assert "STALE" in col.get_note(nid)[config.PITCH]

    # overwrite re-renders the stale one (and only it — the rest already match)
    assert pitch.run_backfill(col, overwrite=True)["filled"] == 1
    pa = col.get_note(nid)[config.PITCH]
    assert "STALE" not in pa and "<svg" in pa

    # a note the dicts can't cover keeps its existing graph (never cleared)
    ek = col.find_notes('"Term:既存"')[0]
    assert col.get_note(ek)[config.PITCH] == "<svg>existing</svg>"
