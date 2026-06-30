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

    add("異変", "いへん")               # empty, in dict -> filled
    add("加味", "かみ")                 # empty, in dict -> filled
    add("裕福", "ゆうふく")             # empty, multi-accent -> stacked graphs
    add("謎語", "なぞご")               # empty, not in dict -> not_found
    add("無読", "")                     # no reading -> skipped
    # a stale/hand-edited graph for an in-dict term is re-asserted from the dicts
    add("旧図", "きゅうず", pa="<svg>STALE</svg>")
    # our own graph the dicts can't cover is kept, never cleared
    add("既存", "きそん", pa="<svg>existing</svg>")
    # non-graph pitch formats (Yomitan span markup) become a graph when covered...
    add("外字", "がいじ", pa='<span style="display:inline;">がいじ</span>')
    # ...and any non-graph content the dicts can't cover is cleared to empty
    add("片仮", "かたかな", pa="No pitch accent data")

    # tiny AccentIndex file; point the engine at it
    idxfile = tmp_path / "accent_index.marshal.gz"
    with gzip.open(idxfile, "wb") as f:
        f.write(marshal.dumps({"異変\tいへん": [0], "加味\tかみ": [1],
                               "裕福\tゆうふく": [0, 1], "旧図\tきゅうず": [0],
                               "外字\tがいじ": [0]}))
    monkeypatch.setattr(pitch, "_INDEX_PATH", str(idxfile))
    yield c
    c.close()


def test_plan_backfill_counts(col):
    plan = pitch.plan_backfill(col)
    assert plan["counts"] == {"filled": 5, "cleared": 1, "not_found": 1, "skipped": 1}
    assert len(plan["samples"]) <= config.SAMPLE_N


def test_backfill_reconciles_to_dicts(col):
    res = pitch.run_backfill(col)
    assert res == {"filled": 5, "cleared": 1, "not_found": 1, "skipped": 1}
    nids = list(col.find_notes(f'note:"{config.NOTETYPE}"'))
    pas = {col.get_note(i)[config.TERM]: col.get_note(i)[config.PITCH] for i in nids}
    assert "<svg" in pas["異変"] and "<svg" in pas["加味"]
    # multi-accent word stacks one graph per accent, <br>-separated
    assert pas["裕福"].count("<svg") == 2 and "<br>" in pas["裕福"]
    # a stale graph for an in-dict term is re-rendered (overwrite is the default)
    assert "STALE" not in pas["旧図"] and "<svg" in pas["旧図"]
    # our own graph the dicts can't cover stays untouched (never cleared)
    assert pas["既存"] == "<svg>existing</svg>"
    # non-graph format replaced by our svg when the dicts cover the term
    assert "<svg" in pas["外字"] and "<span" not in pas["外字"]
    # non-graph content the dicts can't cover is cleared, not preserved
    assert pas["片仮"] == ""
    # uncoverable empties stay empty
    assert pas["謎語"] == "" and pas["無読"] == ""


def test_backfill_is_idempotent(col):
    pitch.run_backfill(col)
    res2 = pitch.run_backfill(col)
    assert res2["filled"] == 0 and res2["cleared"] == 0
