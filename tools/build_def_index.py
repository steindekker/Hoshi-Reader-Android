#!/usr/bin/env python3
"""Build the definition index (defs.sqlite) + scoped dict_styles.css from the
user's Yomitan dictionary folders.

Usage: uv run --with anki python tools/build_def_index.py \
         --dicts research/dict-compare/dicts \
         --db ankialign/user_files/defs.sqlite \
         --styles ankialign/user_files/dict_styles.css
"""
import argparse
import glob
import json
import os
import re
import sqlite3
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from ankialign import render_defs  # render_sc; pure, no anki import

JJ_DICTS = ["sankoku8", "koujien7", "jitsuyo", "daijisen"]
JE_DICT = "jmdict"


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
            html = render_defs.render_sc(e[5])
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
        return ", ".join(f"{selector} {s.strip()}" for s in sels.split(",") if s.strip()) + "{"
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dicts", default="research/dict-compare/dicts")
    ap.add_argument("--db", default="ankialign/user_files/defs.sqlite")
    ap.add_argument("--styles", default="ankialign/user_files/dict_styles.css")
    a = ap.parse_args()
    n = build(a.dicts, a.db, a.styles)
    print(f"indexed {n} rows -> {a.db}")


if __name__ == "__main__":
    main()
