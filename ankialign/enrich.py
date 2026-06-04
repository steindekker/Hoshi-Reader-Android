"""Anki-facing definition enrichment: index lookup + note rewriting."""
import os
import sqlite3
from . import config, convert, render_defs

_COLS = ("term", "reading", "dict_id", "kind", "sense_ord", "html")


def open_index(path=None):
    conn = sqlite3.connect(path or config.DEF_INDEX_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def lookup(conn, term):
    """Return (jj_rows, je_rows) as lists of plain dicts for one term."""
    rows = conn.execute(
        "SELECT term, reading, dict_id, kind, sense_ord, html FROM defs WHERE term=?",
        (term,)).fetchall()
    jj = [dict(zip(_COLS, r)) for r in rows if r["kind"] == "jj"]
    je = [dict(zip(_COLS, r)) for r in rows if r["kind"] == "je"]
    return jj, je


_STYLE_MARKER = "/* ---- AnkiAlign dict styles"


def ensure_fields(col):
    """Add Original Meaning + Definition Source to the Kinomoto notetype if missing."""
    m = col.models.by_name(config.TARGET_NOTETYPE)
    if m is None:
        raise RuntimeError("target notetype missing: " + config.TARGET_NOTETYPE)
    existing = {f["name"] for f in m["flds"]}
    changed = False
    for fname in (config.ORIGINAL_MEANING_FIELD, config.DEFINITION_SOURCE_FIELD):
        if fname not in existing:
            col.models.add_field(m, col.models.new_field(fname))
            changed = True
    if changed:
        col.models.update_dict(m)


def apply_dict_styles(col, styles_path=None):
    """Append the scoped dict stylesheet to the notetype CSS (marker-guarded)."""
    path = styles_path or config.DICT_STYLES_PATH
    if not os.path.exists(path):
        return
    css = open(path, encoding="utf-8").read()
    m = col.models.by_name(config.TARGET_NOTETYPE)
    base = m["css"].split(_STYLE_MARKER, 1)[0]
    m["css"] = base.rstrip() + f"\n\n{_STYLE_MARKER} (generated) ---- */\n" + css
    col.models.update_dict(m)
