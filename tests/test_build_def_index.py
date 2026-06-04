import json
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import build_def_index as b


def _write_dict(root, dict_id, entries, styles=None):
    d = root / dict_id
    d.mkdir(parents=True)
    (d / "index.json").write_text(json.dumps({"title": dict_id, "format": 3}), encoding="utf-8")
    (d / "term_bank_1.json").write_text(json.dumps(entries, ensure_ascii=False), encoding="utf-8")
    if styles:
        (d / "styles.css").write_text(styles, encoding="utf-8")


def test_build_indexes_jj_entries(tmp_path):
    dicts = tmp_path / "dicts"
    _write_dict(dicts, "sankoku8", [
        ["西瓜", "すいか", "", "", 0, [{"type": "structured-content", "content": "うり"}], 1, ""],
    ])
    db = tmp_path / "defs.sqlite"
    b.build(str(dicts), str(db), str(tmp_path / "dict_styles.css"))
    rows = sqlite3.connect(db).execute(
        "SELECT term, reading, dict_id, kind, sense_ord, html FROM defs").fetchall()
    assert rows == [("西瓜", "すいか", "sankoku8", "jj", 0, "うり")]
