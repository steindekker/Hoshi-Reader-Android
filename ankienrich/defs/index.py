"""Open + query the definition index (defs.sqlite)."""
import sqlite3

from ankienrich import paths

_COLS = ("term", "reading", "dict_id", "kind", "sense_ord", "html")


def open_index(path=None) -> sqlite3.Connection:
    conn = sqlite3.connect(path or paths.DEFS_DB)
    conn.row_factory = sqlite3.Row
    return conn


def lookup(conn, term: str) -> tuple[list[dict], list[dict]]:
    """Return (jj_rows, je_rows) as lists of plain dicts for one term."""
    rows = conn.execute(
        "SELECT term, reading, dict_id, kind, sense_ord, html FROM defs WHERE term=?",
        (term,)).fetchall()
    jj = [dict(zip(_COLS, r)) for r in rows if r["kind"] == "jj"]
    je = [dict(zip(_COLS, r)) for r in rows if r["kind"] == "je"]
    return jj, je
