"""Build the frequency index (freq.sqlite) from a Yomitan rank-based frequency
dictionary (JPDB). BUILD-TIME input lives extracted under dicts/jpdb/; absent it
the index is skipped, not failed (deploys without it still run).

JPDB ships two `term_meta` shapes, both indexed here:
  - surface-keyed: `[surface, "freq", {"reading": R, "frequency": {"value": N}}]`
    — the per-word rank (what a learner means by "frequency"); carries its reading.
  - kana-keyed:    `[reading, "freq", {"value": N, "displayValue": "N㋕"}]`
    — the bare-kana rank, used as a fallback when no surface entry exists.
Rows are (key, reading, rank, display); a (term, reading) lookup prefers the
surface+reading row, falling back to the kana row (see index.lookup)."""
import json
import sqlite3
from pathlib import Path

from ankienrich.dbutil import term_banks

DICT_ID = "jpdb"


def _banks(dicts_dir, dict_id) -> list[Path]:
    return term_banks(dicts_dir, dict_id, "term_meta_bank_*.json")


def _parse(meta) -> tuple[str, int, str] | None:
    """(reading, rank, display) from a Yomitan freq meta value, or None. The
    surface shape nests its rank under `frequency` and carries a `reading`; the
    kana shape has a top-level `value` and no reading."""
    if isinstance(meta, dict):
        if "frequency" in meta:                         # surface-keyed
            f = meta["frequency"]
            if isinstance(f, dict) and isinstance(f.get("value"), int):
                return (str(meta.get("reading", "")), f["value"],
                        str(f.get("displayValue", f["value"])))
            return None
        v = meta.get("value")                           # kana-keyed
        if isinstance(v, int):
            return "", v, str(meta.get("displayValue", v))
        return None
    if isinstance(meta, int):
        return "", meta, str(meta)
    if isinstance(meta, str) and meta.isdigit():
        return "", int(meta), meta
    return None


def _schema(conn):
    conn.executescript("""
        DROP TABLE IF EXISTS freq;
        CREATE TABLE freq (key TEXT, reading TEXT, value INTEGER, display TEXT);
    """)


def build(dicts_dir, db_path) -> int:
    """Write {(key, reading) -> (rank, display)} into db_path; keep the smallest
    rank per (key, reading) (most frequent wins on duplicates). Returns the row
    count."""
    from ankienrich import paths
    paths.ensure_var()
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    best: dict[tuple[str, str], tuple[int, str]] = {}
    for bank in _banks(dicts_dir, DICT_ID):
        for e in json.loads(bank.read_text(encoding="utf-8")):
            if len(e) < 3 or e[1] != "freq":
                continue
            parsed = _parse(e[2])
            if parsed is None:
                continue
            reading, value, display = parsed
            k = (e[0], reading)
            if k not in best or value < best[k][0]:
                best[k] = (value, display)
    conn = sqlite3.connect(db_path)
    _schema(conn)  # DROP TABLE IF EXISTS handles a rebuild
    conn.executemany("INSERT INTO freq VALUES (?,?,?,?)",
                     ((key, rd, v, d) for (key, rd), (v, d) in best.items()))
    conn.execute("CREATE INDEX freq_key ON freq (key)")
    conn.commit()
    conn.close()
    return len(best)


def ensure(dicts_dir=None, db_path=None) -> str:
    """'present' if built, 'built' after building, 'no-source' if dicts/jpdb absent."""
    from ankienrich import paths
    dicts_dir = Path(dicts_dir or paths.DICTS_DIR)
    db_path = db_path or paths.FREQ_DB
    if Path(db_path).exists():
        return "present"
    if not _banks(dicts_dir, DICT_ID):
        return "no-source"
    build(str(dicts_dir), db_path)
    return "built"
