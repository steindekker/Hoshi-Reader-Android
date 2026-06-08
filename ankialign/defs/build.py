"""Build the definition index (defs.sqlite) + scoped dict_styles.css from the
user's Yomitan dictionary folders."""
import glob
import json
import os
import re
import sqlite3

from ankialign import config
from ankialign.defs import render

JJ_DICTS = config.JJ_CHAIN
JE_DICT = config.JE_DICT


def _banks(dicts_dir, dict_id):
    return sorted(glob.glob(os.path.join(dicts_dir, dict_id, "term_bank_*.json")))


def _schema(conn):
    conn.executescript("""
        DROP TABLE IF EXISTS defs;
        CREATE TABLE defs (
            term TEXT NOT NULL, reading TEXT NOT NULL, dict_id TEXT NOT NULL,
            kind TEXT NOT NULL, sense_ord INTEGER NOT NULL, html TEXT NOT NULL);
    """)


def _index_jj(conn, dicts_dir, dict_id):
    n = 0
    for bank in _banks(dicts_dir, dict_id):
        rows = []
        for e in json.load(open(bank, encoding="utf-8")):
            html = render.render_sc(e[5])
            if html:
                rows.append((e[0], e[1] or "", dict_id, "jj", 0, html))
        conn.executemany("INSERT INTO defs VALUES (?,?,?,?,?,?)", rows)
        n += len(rows)
    return n


def _glosses(definitions):
    out = []

    def walk(n):
        if isinstance(n, dict):
            if n.get("data", {}).get("content") == "glossary":
                c = n.get("content")
                for li in (c if isinstance(c, list) else [c]):
                    if isinstance(li, dict) and li.get("tag") == "li" and isinstance(li.get("content"), str):
                        out.append(li["content"])
            else:
                walk(n.get("content"))
        elif isinstance(n, list):
            for x in n:
                walk(x)

    walk(definitions)
    return out


def _sense_ord(defTags, fallback):
    tok = (defTags or "").split(" ")[0]
    return int(tok) if tok.isdigit() else fallback


def _index_je(conn, dicts_dir):
    n = 0
    for bank in _banks(dicts_dir, JE_DICT):
        rows = []
        for i, e in enumerate(json.load(open(bank, encoding="utf-8"))):
            gl = _glosses(e[5])
            if gl:
                rows.append((e[0], e[1] or "", JE_DICT, "je",
                             _sense_ord(e[2], i + 1), "; ".join(gl)))
        conn.executemany("INSERT INTO defs VALUES (?,?,?,?,?,?)", rows)
        n += len(rows)
    return n


def _scope_css(css, selector):
    """Prefix every selector list with `selector ` (skip @-rules)."""
    def repl(m):
        sels = m.group(1)
        if sels.strip().startswith("@"):
            return m.group(0)
        return ", ".join(f"{selector} {s.strip()}" for s in sels.split(",") if s.strip()) + " {"
    return re.sub(r"([^{}]+)\{", repl, css)


def _build_styles(dicts_dir, styles_path):
    chunks = []
    for dict_id in JJ_DICTS:
        sp = os.path.join(dicts_dir, dict_id, "styles.css")
        if os.path.exists(sp):
            scoped = _scope_css(open(sp, encoding="utf-8").read(), f'.aa-jj[data-dict="{dict_id}"]')
            chunks.append(f"/* {dict_id} */\n{scoped}")
    os.makedirs(os.path.dirname(styles_path), exist_ok=True)
    open(styles_path, "w", encoding="utf-8").write("\n\n".join(chunks))


def build(dicts_dir, db_path, styles_path):
    from ankialign import paths
    paths.ensure_var()
    os.makedirs(os.path.dirname(db_path), exist_ok=True)
    conn = sqlite3.connect(db_path)
    _schema(conn)
    total = 0
    for dict_id in JJ_DICTS:
        if _banks(dicts_dir, dict_id):
            total += _index_jj(conn, dicts_dir, dict_id)
    if _banks(dicts_dir, JE_DICT):
        total += _index_je(conn, dicts_dir)
    conn.execute("CREATE INDEX defs_key ON defs (term, reading)")
    conn.commit()
    conn.close()
    _build_styles(dicts_dir, styles_path)
    return total


def ensure(dicts_dir=None, db_path=None, styles_path=None) -> str:
    """Return 'present' if index exists, 'built' after building, 'no-source' if dicts missing."""
    from ankialign import paths
    dicts_dir = dicts_dir or paths.DICTS_DIR
    db_path = db_path or paths.DEFS_DB
    styles_path = styles_path or paths.DICT_STYLES
    if os.path.exists(db_path):
        return "present"
    if not os.path.isdir(dicts_dir) or not os.listdir(dicts_dir):
        return "no-source"
    build(dicts_dir, db_path, styles_path)
    return "built"
