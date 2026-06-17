"""Open + query the frequency index (freq.sqlite). Keyed by headword string;
a (term, reading) lookup tries the surface form first, then the kana reading."""
import sqlite3

from ankienrich import paths


def open_index(path=None) -> sqlite3.Connection:
    conn = sqlite3.connect(path or paths.FREQ_DB)
    conn.row_factory = sqlite3.Row
    return conn


def lookup(conn, term: str, reading: str) -> tuple[int, str] | None:
    """(rank, display) for term (surface preferred) or reading, else None."""
    for key in (term, reading):
        if not key:
            continue
        row = conn.execute(
            "SELECT value, display FROM freq WHERE key=?", (key,)).fetchone()
        if row is not None:
            return row["value"], row["display"]
    return None
