"""Open + query the frequency index (freq.sqlite). Rows are (key, reading, rank,
display). A (term, reading) lookup prefers the surface row whose reading matches
(the per-word rank), else the surface's most-frequent row, else the bare-kana
row keyed by the reading."""
import sqlite3

from ankienrich import paths
from ankienrich.dbutil import open_row_db


def open_index(path=None) -> sqlite3.Connection:
    return open_row_db(path or paths.FREQ_DB)


def lookup(conn, term: str, reading: str) -> tuple[int, str] | None:
    """(rank, display) for (term, reading), else None. Surface rows for `term` win
    — preferring an exact reading match so a kanji's distinct readings get distinct
    ranks — and a bare-kana row keyed by `reading` is the fallback."""
    rows = conn.execute(
        "SELECT reading, value, display FROM freq WHERE key=?", (term,)).fetchall()
    if rows:
        exact = [r for r in rows if r["reading"] == reading]
        pick = min(exact or rows, key=lambda r: r["value"])
        return pick["value"], pick["display"]
    if reading:
        row = conn.execute(
            "SELECT value, display FROM freq WHERE key=? ORDER BY value LIMIT 1",
            (reading,)).fetchone()
        if row is not None:
            return row["value"], row["display"]
    return None
