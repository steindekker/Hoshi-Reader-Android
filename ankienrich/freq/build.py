"""Build the frequency index (freq.sqlite) from a Yomitan rank-based frequency
dictionary (JPDB). BUILD-TIME input lives extracted under dicts/jpdb/; absent it
the index is skipped, not failed (deploys without it still run). Entries are
`[key, "freq", value]` where key is a surface OR kana headword — we index both so
a (term, reading) lookup can fall back from surface to reading."""
import json
import sqlite3
from pathlib import Path

DICT_ID = "jpdb"


def _banks(dicts_dir, dict_id) -> list[Path]:
    return sorted(Path(dicts_dir, dict_id).glob("term_meta_bank_*.json"))


def _value(meta) -> tuple[int, str] | None:
    """Normalise a Yomitan freq meta value to (rank, display). Handles the int,
    string, and {value, displayValue} forms; returns None for anything else."""
    if isinstance(meta, dict):
        v = meta.get("value")
        if isinstance(v, int):
            return v, str(meta.get("displayValue", v))
        return None
    if isinstance(meta, int):
        return meta, str(meta)
    if isinstance(meta, str) and meta.isdigit():
        return int(meta), meta
    return None


def _schema(conn):
    conn.executescript("""
        DROP TABLE IF EXISTS freq;
        CREATE TABLE freq (key TEXT PRIMARY KEY, value INTEGER, display TEXT);
    """)


def build(dicts_dir, db_path) -> int:
    """Write {key -> (rank, display)} into db_path; keep the smallest rank per key
    (most frequent wins on duplicates). Returns the row count."""
    from ankienrich import paths
    paths.ensure_var()
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    best: dict[str, tuple[int, str]] = {}
    for bank in _banks(dicts_dir, DICT_ID):
        for e in json.loads(bank.read_text(encoding="utf-8")):
            if len(e) < 3 or e[1] != "freq":
                continue
            parsed = _value(e[2])
            if parsed is None:
                continue
            key = e[0]
            if key not in best or parsed[0] < best[key][0]:
                best[key] = parsed
    if Path(db_path).exists():
        Path(db_path).unlink()
    conn = sqlite3.connect(db_path)
    _schema(conn)
    conn.executemany("INSERT INTO freq VALUES (?,?,?)",
                     ((k, v, d) for k, (v, d) in best.items()))
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
