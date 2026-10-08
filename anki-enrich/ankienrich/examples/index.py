"""Open + query the example index (examples.sqlite), with priority-fill selection."""
import sqlite3

from ankienrich import config, paths
from ankienrich.dbutil import by_reading as _by_reading, open_row_db
from ankienrich.examples import parse


def open_index(path=None) -> sqlite3.Connection:
    return open_row_db(path or paths.EXAMPLES_DB)


def select(conn, term: str, reading: str,
           sources=None, cap=None) -> list[dict]:
    """Up to `cap` examples for one card: walk `sources` in priority order, prefer
    reading-matching rows, dedupe across sources by normalized JP, skip any example
    longer than EXAMPLE_MAX_LEN. Returns dicts with jp/en/source/label keys, source
    order preserved within each dict."""
    sources = sources or config.EXAMPLE_SOURCES
    cap = cap or config.EXAMPLE_CAP
    maxlen = config.EXAMPLE_MAX_LEN
    rows = conn.execute(
        "SELECT term, reading, dict_id, ord, jp, en, plain FROM examples WHERE term=?",
        (term,)).fetchall()
    chosen, seen = [], set()
    for src in sources:
        src_rows = sorted((r for r in rows if r["dict_id"] == src), key=lambda r: r["ord"])
        for r in _by_reading(src_rows, reading):
            if maxlen and len(r["plain"]) > maxlen:
                continue
            key = parse.norm(r["plain"])
            if not key or key in seen:
                continue
            seen.add(key)
            chosen.append({"jp": r["jp"], "en": r["en"], "source": src,
                           "label": config.EX_LABELS.get(src, src)})
            if len(chosen) >= cap:
                return chosen
    return chosen
