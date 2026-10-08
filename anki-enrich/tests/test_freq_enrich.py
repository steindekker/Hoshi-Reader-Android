import json
import os
from pathlib import Path

from anki.collection import Collection

from ankienrich import config
from ankienrich.freq import build as freq_build, index as freq_index, enrich


def _freq_conn(tmp):
    """Tiny JPDB-shaped index: 林檎/りんご #13430, 方/ほう #100."""
    dicts = Path(tmp) / "dicts"
    (dicts / "jpdb").mkdir(parents=True)
    bank = [
        ["林檎", "freq", {"reading": "りんご", "frequency": {"value": 13430, "displayValue": "13430"}}],
        ["方", "freq", {"reading": "ほう", "frequency": {"value": 100, "displayValue": "100"}}],
    ]
    (dicts / "jpdb" / "term_meta_bank_1.json").write_text(
        json.dumps(bank, ensure_ascii=False), encoding="utf-8")
    db = Path(tmp) / "freq.sqlite"
    freq_build.build(str(dicts), str(db))
    return freq_index.open_index(str(db))


def _kino_col(tmp):
    col = Collection(os.path.join(tmp, "c.anki2"))
    m = col.models.new(config.NOTETYPE)
    for fname in (config.TERM, config.READING, config.FREQUENCY):
        col.models.add_field(m, col.models.new_field(fname))
    t = col.models.new_template("jidoujisho Kinomoto")
    t["qfmt"], t["afmt"] = "{{Term}}", "{{Reading}}"
    col.models.add_template(m, t)
    col.models.add(m)
    return col


def _add(col, term, reading, freq=""):
    n = col.new_note(col.models.by_name(config.NOTETYPE))
    n[config.TERM] = term
    n[config.READING] = reading
    n[config.FREQUENCY] = freq
    col.add_note(n, col.decks.id("Default"))
    return n.id


def test_fills_empty_frequency(tmp_path):
    conn = _freq_conn(str(tmp_path))
    col = _kino_col(str(tmp_path))
    try:
        nid = _add(col, "林檎", "りんご")
        res = enrich.enrich_notes(col, conn)
        assert res == {"written": 1, "none": 0}
        assert col.get_note(nid)[config.FREQUENCY] == "13430"
    finally:
        col.close(); conn.close()


def test_idempotent_no_rewrite(tmp_path):
    conn = _freq_conn(str(tmp_path))
    col = _kino_col(str(tmp_path))
    try:
        _add(col, "林檎", "りんご", freq="13430")
        res = enrich.enrich_notes(col, conn)
        assert res["written"] == 0
    finally:
        col.close(); conn.close()


def test_asserts_over_stale_value(tmp_path):
    # the "no gate" behaviour: a differing existing value is corrected, no overwrite flag
    conn = _freq_conn(str(tmp_path))
    col = _kino_col(str(tmp_path))
    try:
        nid = _add(col, "林檎", "りんご", freq="999")
        res = enrich.enrich_notes(col, conn)
        assert res["written"] == 1
        assert col.get_note(nid)[config.FREQUENCY] == "13430"
    finally:
        col.close(); conn.close()


def test_no_rank_left_empty(tmp_path):
    conn = _freq_conn(str(tmp_path))
    col = _kino_col(str(tmp_path))
    try:
        nid = _add(col, "未知語", "みちご")
        res = enrich.enrich_notes(col, conn)
        assert res == {"written": 0, "none": 1}
        assert col.get_note(nid)[config.FREQUENCY] == ""
    finally:
        col.close(); conn.close()


def test_build_plan_counts_without_mutating(tmp_path):
    conn = _freq_conn(str(tmp_path))
    col = _kino_col(str(tmp_path))
    try:
        nid_fill = _add(col, "林檎", "りんご")       # would change
        _add(col, "方", "ほう", freq="100")          # already correct
        _add(col, "未知語", "みちご")                # no rank
        plan = enrich.build_plan(col, conn)
        assert plan["to_write"] == 1
        assert plan["none"] == 1
        assert col.get_note(nid_fill)[config.FREQUENCY] == ""  # dry run wrote nothing
    finally:
        col.close(); conn.close()
