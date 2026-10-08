"""Build the definition index (defs.sqlite) + scoped dict_styles.css from the
user's Yomitan dictionary folders."""
import json
import sqlite3
from pathlib import Path

import tinycss2

from ankienrich import config
from ankienrich.dbutil import term_banks as _banks
from ankienrich.defs import render

JJ_DICTS = config.JJ_CHAIN
JE_DICT = config.JE_DICT


def _load_bank(bank: Path) -> list:
    return json.loads(bank.read_text(encoding="utf-8"))


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
        for e in _load_bank(bank):
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
        for i, e in enumerate(_load_bank(bank)):
            gl = _glosses(e[5])
            if gl:
                rows.append((e[0], e[1] or "", JE_DICT, "je",
                             _sense_ord(e[2], i + 1), "; ".join(gl)))
        conn.executemany("INSERT INTO defs VALUES (?,?,?,?,?,?)", rows)
        n += len(rows)
    return n


def _scope_css(css, selector):
    """Prefix every selector with `selector ` so dict styles stay inside their
    container. Selector lists split on top-level commas only (commas inside
    :not()/[…] are preserved); @-rule blocks (e.g. @media) are scoped recursively,
    statement at-rules (@import/@charset) pass through untouched."""
    return _scope_rules(tinycss2.parse_stylesheet(css, skip_comments=True), selector)


def _scope_rules(rules, selector):
    out = []
    for rule in rules:
        if rule.type == "qualified-rule":
            sels = ", ".join(f"{selector} {tinycss2.serialize(g).strip()}"
                             for g in _selector_groups(rule.prelude))
            out.append(f"{sels} {{{tinycss2.serialize(rule.content)}}}")
        elif rule.type == "at-rule":
            prelude = tinycss2.serialize(rule.prelude).strip()
            if rule.content is None:  # statement at-rule, e.g. @import url(...);
                out.append(f"@{rule.lower_at_keyword} {prelude};")
            else:                     # block at-rule, e.g. @media: scope its body
                inner = _scope_rules(
                    tinycss2.parse_stylesheet(tinycss2.serialize(rule.content)), selector)
                out.append(f"@{rule.lower_at_keyword} {prelude} {{\n{inner}\n}}")
    return "\n".join(out)


def _selector_groups(prelude):
    """Split a selector-list prelude into groups on top-level comma tokens."""
    group, groups = [], []
    for tok in prelude:
        if tok.type == "literal" and tok.value == ",":
            groups.append(group)
            group = []
        else:
            group.append(tok)
    groups.append(group)
    return [g for g in groups if tinycss2.serialize(g).strip()]


def _build_styles(dicts_dir, styles_path):
    chunks = []
    for dict_id in JJ_DICTS:
        sp = Path(dicts_dir, dict_id, "styles.css")
        if sp.exists():
            scoped = _scope_css(sp.read_text(encoding="utf-8"), f'.aa-jj[data-dict="{dict_id}"]')
            chunks.append(f"/* {dict_id} */\n{scoped}")
    out = Path(styles_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n\n".join(chunks), encoding="utf-8")


def build(dicts_dir, db_path, styles_path):
    from ankienrich import paths
    paths.ensure_var()
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
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
    from ankienrich import paths
    dicts_dir = Path(dicts_dir or paths.DICTS_DIR)
    db_path = db_path or paths.DEFS_DB
    styles_path = styles_path or paths.DICT_STYLES
    if Path(db_path).exists():
        return "present"
    if not dicts_dir.is_dir() or not any(dicts_dir.iterdir()):
        return "no-source"
    build(dicts_dir, db_path, styles_path)
    return "built"
