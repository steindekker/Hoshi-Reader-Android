# Definition Enrichment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace each Kinomoto vocab card's cluttered `Meaning` with a clean Japanese-base definition (J-J dictionary, first hit in the chain 三省堂→広辞苑→実用→大辞泉) plus short numbered English glosses (JMdict, cap 4), as an offline, reversible, opt-in add-on pass.

**Architecture:** Two phases. (1) An offline build script turns the user's Yomitan dictionary folders into a compact `defs.sqlite` index + a scoped `dict_styles.css`. (2) In-Anki, a pure `render_defs` module formats definitions and an anki-facing `enrich` module rewrites `Meaning` (stashing the original, tagging, idempotent), driven by a preview dialog with a forced backup — mirroring the existing `convert.py`/`preview.py`/`markup.py` split.

**Tech Stack:** Python 3.13, `anki` package (headless-testable, no `aqt` in logic modules), `aqt`/Qt for the dialog, stdlib `sqlite3`, `pytest`, `uv`. Source dictionaries are Yomitan format-3 term banks already unzipped under `research/dict-compare/dicts/`.

**Spec:** `docs/superpowers/specs/2026-06-04-definition-enrichment-design.md`

---

## File structure

| File | Responsibility |
|---|---|
| `ankialign/config.py` (modify) | enrichment constants: chain, dict labels, field names, tags, paths, caps |
| `ankialign/render_defs.py` (create) | **pure** (no anki): `render_sc`, `numbered_glosses`, `choose_jj`, `assemble_meaning`, `source_label` |
| `ankialign/enrich.py` (create) | anki-facing: `open_index`, `lookup`, `ensure_fields`, `apply_dict_styles`, `build_plan`, `enrich_notes` |
| `ankialign/enrich_preview.py` (create) | Qt dialog: dry-run summary + backup + main-thread run |
| `ankialign/__init__.py` (modify) | add the second Tools-menu action |
| `ankialign/kinomoto.css` (modify) | static `.aa-jj`/`.aa-je` styling + furigana polish (CSS layer 2) |
| `tools/build_def_index.py` (create) | offline: dict folders → `user_files/defs.sqlite` + `user_files/dict_styles.css` |
| `tests/test_render_defs.py` (create) | pure-function unit tests |
| `tests/test_enrich.py` (create) | fixture-collection tests |
| `tests/test_build_def_index.py` (create) | build smoke test on a tiny dict fixture |

`ankialign/user_files/` holds the generated `defs.sqlite` + `dict_styles.css` (add to `.gitignore`; Anki preserves `user_files/` across add-on updates).

Data shapes (used throughout):
- **SQLite `defs` row** → exposed as a dict: `{"term": str, "reading": str, "dict_id": str, "kind": "jj"|"je", "sense_ord": int, "html": str}`.
- **`render_sc` input** = a Yomitan structured-content node: a `str`, a `list`, or a `dict` with keys among `type`, `tag`, `content`, `style`, `data`, `href`.

---

## Task 1: Enrichment config constants

**Files:**
- Modify: `ankialign/config.py`
- Test: `tests/test_config.py`

- [ ] **Step 1: Write the failing test**

Add to `tests/test_config.py`:

```python
def test_enrichment_constants():
    from ankialign import config
    assert config.JJ_CHAIN == ["sankoku8", "koujien7", "jitsuyo", "daijisen"]
    assert config.JE_DICT == "jmdict"
    assert config.GLOSS_CAP == 4
    assert config.ENGLISH_COLLAPSED_DEFAULT is False
    assert config.DICT_LABELS["sankoku8"] == "三省堂"
    assert config.DICT_LABELS["jmdict"] == "JMdict"
    assert config.ORIGINAL_MEANING_FIELD == "Original Meaning"
    assert config.DEFINITION_SOURCE_FIELD == "Definition Source"
    assert config.ENRICHED_TAG == "ankialign::enriched"
    assert config.NO_DEF_TAG == "ankialign::no-definition"
    assert config.NO_HIT_DROP_THRESHOLD == 20
    assert config.DEF_INDEX_PATH.endswith("user_files/defs.sqlite")
    assert config.DICT_STYLES_PATH.endswith("user_files/dict_styles.css")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ~/projects/anki-align && uv run --with anki pytest tests/test_config.py::test_enrichment_constants -v`
Expected: FAIL with `AttributeError: module 'ankialign.config' has no attribute 'JJ_CHAIN'`

- [ ] **Step 3: Add the constants**

Append to `ankialign/config.py` (add `import os` at the top of the file if absent):

```python
import os

# --- definition enrichment ---
JJ_CHAIN = ["sankoku8", "koujien7", "jitsuyo", "daijisen"]  # first hit wins
JE_DICT = "jmdict"
GLOSS_CAP = 4
ENGLISH_COLLAPSED_DEFAULT = False  # English <details> rendered open (visible but collapsible)

DICT_LABELS = {
    "sankoku8": "三省堂", "koujien7": "広辞苑", "jitsuyo": "実用",
    "daijisen": "大辞泉", "jmdict": "JMdict",
}

ORIGINAL_MEANING_FIELD = "Original Meaning"
DEFINITION_SOURCE_FIELD = "Definition Source"
ENRICHED_TAG = "ankialign::enriched"
NO_DEF_TAG = "ankialign::no-definition"
NO_HIT_DROP_THRESHOLD = 20

_USER_FILES = os.path.join(os.path.dirname(__file__), "user_files")
DEF_INDEX_PATH = os.path.join(_USER_FILES, "defs.sqlite")
DICT_STYLES_PATH = os.path.join(_USER_FILES, "dict_styles.css")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --with anki pytest tests/test_config.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ankialign/config.py tests/test_config.py
git commit -m "feat(enrich): add enrichment config constants"
```

---

## Task 2: `render_sc` — Yomitan structured content → HTML

**Files:**
- Create: `ankialign/render_defs.py`
- Test: `tests/test_render_defs.py`

- [ ] **Step 1: Write the failing test**

Create `tests/test_render_defs.py`:

```python
from ankialign import render_defs as rd


def test_render_sc_string_and_list():
    assert rd.render_sc("あ") == "あ"
    assert rd.render_sc(["a", "b"]) == "ab"
    assert rd.render_sc("a\nb") == "a<br>b"          # newlines -> <br>
    assert rd.render_sc("<x>") == "&lt;x&gt;"        # escaped


def test_render_sc_tag_style_data():
    node = {"tag": "span", "style": {"fontWeight": "bold", "fontSize": "0.8em"},
            "data": {"name": "見出"}, "content": "語"}
    out = rd.render_sc(node)
    assert out == '<span style="font-weight:bold;font-size:0.8em" data-name="見出">語</span>'


def test_render_sc_ruby_and_void_and_image():
    ruby = {"tag": "ruby", "content": [{"tag": "rb", "content": "西瓜"}, {"tag": "rt", "content": "すいか"}]}
    assert rd.render_sc(ruby) == "<ruby><rb>西瓜</rb><rt>すいか</rt></ruby>"
    assert rd.render_sc({"tag": "br"}) == "<br>"             # void tag, no closing
    assert rd.render_sc({"type": "image", "path": "x.png"}) == ""  # images dropped
    assert rd.render_sc({"type": "structured-content", "content": "z"}) == "z"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --with anki pytest tests/test_render_defs.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'ankialign.render_defs'`

- [ ] **Step 3: Implement `render_sc`**

Create `ankialign/render_defs.py`:

```python
"""Pure rendering/formatting for definition enrichment. No anki/aqt imports."""
import html
import re

_VOID = {"br", "hr", "img"}


def _kebab(k: str) -> str:
    return re.sub(r"[A-Z]", lambda m: "-" + m.group(0).lower(), k)


def render_sc(node) -> str:
    """Yomitan structured-content node -> HTML.

    Preserves inline `style` (camelCase->kebab) and `data-*` attributes so each
    dictionary's native look survives; renders ruby/rt and lists; drops images.
    """
    if node is None:
        return ""
    if isinstance(node, str):
        return html.escape(node).replace("\n", "<br>")
    if isinstance(node, list):
        return "".join(render_sc(c) for c in node)
    if isinstance(node, dict):
        t = node.get("type")
        if t == "structured-content":
            return render_sc(node.get("content"))
        if t == "text":
            return html.escape(node.get("text", "")).replace("\n", "<br>")
        if t == "image":
            return ""
        tag = node.get("tag")
        if tag is None:
            return render_sc(node.get("content"))
        attrs = ""
        style = node.get("style")
        if isinstance(style, dict):
            decls = [f"{_kebab(k)}:{v}" for k, v in style.items()]
            if decls:
                attrs += f' style="{html.escape(";".join(decls), quote=True)}"'
        data = node.get("data")
        if isinstance(data, dict):
            for k, v in data.items():
                attrs += f' data-{_kebab(k)}="{html.escape(str(v), quote=True)}"'
        if tag == "a" and node.get("href"):
            attrs += f' href="{html.escape(node["href"], quote=True)}"'
        if tag in _VOID:
            return f"<{tag}{attrs}>"
        return f"<{tag}{attrs}>{render_sc(node.get('content'))}</{tag}>"
    return ""
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --with anki pytest tests/test_render_defs.py -v`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add ankialign/render_defs.py tests/test_render_defs.py
git commit -m "feat(enrich): structured-content renderer"
```

---

## Task 3: `numbered_glosses` — JMdict senses → numbered English

**Files:**
- Modify: `ankialign/render_defs.py`
- Test: `tests/test_render_defs.py`

JMdict `je` rows each carry one sense's `"; "`-joined glosses in `html`, with `sense_ord`. Multiple rows may share `(term)` across readings; select by reading first.

- [ ] **Step 1: Write the failing test**

Add to `tests/test_render_defs.py`:

```python
def _je(dict_id, reading, sense_ord, html):
    return {"term": "x", "reading": reading, "dict_id": dict_id,
            "kind": "je", "sense_ord": sense_ord, "html": html}


def test_numbered_glosses_orders_and_caps():
    rows = [
        _je("jmdict", "こうしょう", 2, "connection; relationship"),
        _je("jmdict", "こうしょう", 1, "negotiations; bargaining"),
    ]
    assert rd.numbered_glosses(rows, "こうしょう", cap=4) == \
        "1. negotiations; bargaining<br>2. connection; relationship"


def test_numbered_glosses_cap_and_reading_filter():
    rows = [_je("jmdict", "かける", i, f"g{i}") for i in range(1, 7)]
    rows += [_je("jmdict", "OTHER", 1, "wrong")]
    out = rd.numbered_glosses(rows, "かける", cap=4)
    assert out == "1. g1<br>2. g2<br>3. g3<br>4. g4"   # capped at 4, wrong reading excluded


def test_numbered_glosses_empty():
    assert rd.numbered_glosses([], "あ", cap=4) == ""
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --with anki pytest tests/test_render_defs.py::test_numbered_glosses_orders_and_caps -v`
Expected: FAIL with `AttributeError: module 'ankialign.render_defs' has no attribute 'numbered_glosses'`

- [ ] **Step 3: Implement**

Add to `ankialign/render_defs.py`:

```python
def _by_reading(rows, reading):
    """Rows matching the note reading; fall back to reading-less, then all."""
    exact = [r for r in rows if r["reading"] == reading]
    if exact:
        return exact
    blank = [r for r in rows if r["reading"] == ""]
    return blank or rows


def numbered_glosses(je_rows, reading, cap=4) -> str:
    rows = sorted(_by_reading(je_rows, reading), key=lambda r: r["sense_ord"])[:cap]
    return "<br>".join(f"{i}. {r['html']}" for i, r in enumerate(rows, 1))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --with anki pytest tests/test_render_defs.py -v`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add ankialign/render_defs.py tests/test_render_defs.py
git commit -m "feat(enrich): numbered JMdict glosses"
```

---

## Task 4: `choose_jj` — chain first-hit + reading disambiguation

**Files:**
- Modify: `ankialign/render_defs.py`
- Test: `tests/test_render_defs.py`

- [ ] **Step 1: Write the failing test**

Add to `tests/test_render_defs.py`:

```python
CHAIN = ["sankoku8", "koujien7", "jitsuyo", "daijisen"]


def _jj(dict_id, reading, html):
    return {"term": "x", "reading": reading, "dict_id": dict_id,
            "kind": "jj", "sense_ord": 0, "html": html}


def test_choose_jj_first_in_chain_wins():
    rows = [_jj("daijisen", "あ", "D"), _jj("koujien7", "あ", "K"), _jj("sankoku8", "あ", "S")]
    assert rd.choose_jj(rows, "あ", CHAIN) == ("sankoku8", "S")


def test_choose_jj_reading_disambiguation():
    rows = [_jj("sankoku8", "はし", "bridge"), _jj("sankoku8", "ばし", "wrong")]
    assert rd.choose_jj(rows, "はし", CHAIN) == ("sankoku8", "bridge")


def test_choose_jj_blank_reading_fallback_then_skip():
    rows = [_jj("sankoku8", "", "S-noreading"), _jj("koujien7", "あ", "K")]
    assert rd.choose_jj(rows, "あ", CHAIN) == ("sankoku8", "S-noreading")
    assert rd.choose_jj([], "あ", CHAIN) is None
    # dict not in chain is ignored
    assert rd.choose_jj([_jj("smk8", "あ", "X")], "あ", CHAIN) is None
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --with anki pytest tests/test_render_defs.py::test_choose_jj_first_in_chain_wins -v`
Expected: FAIL with `AttributeError: ... 'choose_jj'`

- [ ] **Step 3: Implement**

Add to `ankialign/render_defs.py`:

```python
def choose_jj(jj_rows, reading, chain):
    """First dictionary in `chain` order that has a reading-matching entry.

    Returns (dict_id, html) or None. Reading match prefers exact reading, then a
    reading-less entry, then any entry from that dict.
    """
    for dict_id in chain:
        rows = [r for r in jj_rows if r["dict_id"] == dict_id]
        if not rows:
            continue
        chosen = _by_reading(rows, reading)
        if chosen:
            return (dict_id, chosen[0]["html"])
    return None
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --with anki pytest tests/test_render_defs.py -v`
Expected: PASS (9 tests)

- [ ] **Step 5: Commit**

```bash
git add ankialign/render_defs.py tests/test_render_defs.py
git commit -m "feat(enrich): J-J chain selection with reading disambiguation"
```

---

## Task 5: `assemble_meaning` + `source_label`

**Files:**
- Modify: `ankialign/render_defs.py`
- Test: `tests/test_render_defs.py`

- [ ] **Step 1: Write the failing test**

Add to `tests/test_render_defs.py`:

```python
def test_assemble_meaning_jj_and_je():
    out = rd.assemble_meaning(("sankoku8", "<b>def</b>"), "1. a; b", english_open=True)
    assert out == ('<div class="aa-jj" data-dict="sankoku8"><b>def</b></div>'
                   '<details class="aa-je" open><summary>English</summary>1. a; b</details>')


def test_assemble_meaning_collapsed_and_jj_only_and_empty():
    assert rd.assemble_meaning(None, "1. a", english_open=False) == \
        '<details class="aa-je"><summary>English</summary>1. a</details>'
    assert rd.assemble_meaning(("sankoku8", "x"), "") == '<div class="aa-jj" data-dict="sankoku8">x</div>'
    assert rd.assemble_meaning(None, "") == ""


def test_source_label():
    labels = {"sankoku8": "三省堂", "jmdict": "JMdict"}
    assert rd.source_label(("sankoku8", "x"), has_je=True, labels=labels) == "三省堂"
    assert rd.source_label(None, has_je=True, labels=labels) == "JMdict"
    assert rd.source_label(None, has_je=False, labels=labels) == "—"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --with anki pytest tests/test_render_defs.py::test_assemble_meaning_jj_and_je -v`
Expected: FAIL with `AttributeError: ... 'assemble_meaning'`

- [ ] **Step 3: Implement**

Add to `ankialign/render_defs.py`:

```python
def assemble_meaning(jj, je_html, english_open=True) -> str:
    """jj = (dict_id, html) or None; je_html = numbered string or ''.

    English is a collapsible <details> (tap-to-toggle on desktop + AnkiDroid).
    english_open bakes the `open` attribute (default-visible vs default-collapsed).
    """
    parts = []
    if jj:
        parts.append(f'<div class="aa-jj" data-dict="{jj[0]}">{jj[1]}</div>')
    if je_html:
        op = " open" if english_open else ""
        parts.append(f'<details class="aa-je"{op}><summary>English</summary>{je_html}</details>')
    return "".join(parts)


def source_label(jj, has_je, labels) -> str:
    if jj:
        return labels.get(jj[0], jj[0])
    if has_je:
        return labels.get("jmdict", "JMdict")
    return "—"
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --with anki pytest tests/test_render_defs.py -v`
Expected: PASS (12 tests)

- [ ] **Step 5: Commit**

```bash
git add ankialign/render_defs.py tests/test_render_defs.py
git commit -m "feat(enrich): assemble Meaning + source label"
```

---

## Task 6: `build_def_index.py` — SQLite schema + J-J indexing

**Files:**
- Create: `tools/build_def_index.py`
- Test: `tests/test_build_def_index.py`

The build reads Yomitan format-3 term banks from a dicts dir (each subfolder named by `dict_id`, containing `term_bank_*.json`). Format-3 entry = `[expr, reading, defTags, rules, score, [definitions], seq, termTags]`.

- [ ] **Step 1: Write the failing test**

Create `tests/test_build_def_index.py`:

```python
import json
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import build_def_index as b


def _write_dict(root, dict_id, entries, styles=None):
    d = root / dict_id
    d.mkdir(parents=True)
    (d / "index.json").write_text(json.dumps({"title": dict_id, "format": 3}), encoding="utf-8")
    (d / "term_bank_1.json").write_text(json.dumps(entries, ensure_ascii=False), encoding="utf-8")
    if styles:
        (d / "styles.css").write_text(styles, encoding="utf-8")


def test_build_indexes_jj_entries(tmp_path):
    dicts = tmp_path / "dicts"
    _write_dict(dicts, "sankoku8", [
        ["西瓜", "すいか", "", "", 0, [{"type": "structured-content", "content": "うり"}], 1, ""],
    ])
    db = tmp_path / "defs.sqlite"
    b.build(str(dicts), str(db), str(tmp_path / "dict_styles.css"))
    rows = sqlite3.connect(db).execute(
        "SELECT term, reading, dict_id, kind, sense_ord, html FROM defs").fetchall()
    assert rows == [("西瓜", "すいか", "sankoku8", "jj", 0, "うり")]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --with anki pytest tests/test_build_def_index.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'build_def_index'`

- [ ] **Step 3: Implement schema + J-J indexing**

Create `tools/build_def_index.py`:

```python
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
```

- [ ] **Step 4: Add the `build` entrypoint (J-J only for now)**

Append to `tools/build_def_index.py`:

```python
def build(dicts_dir, db_path, styles_path):
    os.makedirs(os.path.dirname(db_path), exist_ok=True)
    conn = sqlite3.connect(db_path)
    _schema(conn)
    total = 0
    for dict_id in JJ_DICTS:
        if _banks(dicts_dir, dict_id):
            total += _index_jj(conn, dicts_dir, dict_id)
    conn.execute("CREATE INDEX defs_key ON defs (term, reading)")
    conn.commit()
    conn.close()
    return total


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dicts", default="research/dict-compare/dicts")
    ap.add_argument("--db", default="ankialign/user_files/defs.sqlite")
    ap.add_argument("--styles", default="ankialign/user_files/dict_styles.css")
    a = ap.parse_args()
    n = build(a.dicts, a.db, a.styles)
    print(f"indexed {n} J-J rows -> {a.db}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 5: Run test to verify it passes**

Run: `uv run --with anki pytest tests/test_build_def_index.py -v`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add tools/build_def_index.py tests/test_build_def_index.py
git commit -m "feat(enrich): build script schema + J-J indexing"
```

---

## Task 7: `build_def_index.py` — JMdict sense indexing

**Files:**
- Modify: `tools/build_def_index.py`
- Test: `tests/test_build_def_index.py`

JMdict stores one sense per entry; the sense number is the first token of `defTags` (e.g. `"1 n vs"`), and `score` descends with sense order. Glosses are the `<li>` texts under the `data.content=="glossary"` node.

- [ ] **Step 1: Write the failing test**

Add to `tests/test_build_def_index.py`:

```python
def _gloss_entry(expr, reading, defTags, score, glosses):
    sc = {"type": "structured-content", "content": {
        "tag": "ul", "data": {"content": "glossary"},
        "content": [{"tag": "li", "content": g} for g in glosses]}}
    return [expr, reading, defTags, "", score, [sc], 1, ""]


def test_build_indexes_jmdict_senses(tmp_path):
    dicts = tmp_path / "dicts"
    _write_dict(dicts, "jmdict", [
        _gloss_entry("交渉", "こうしょう", "1 n vs", 1999800, ["negotiations", "bargaining"]),
        _gloss_entry("交渉", "こうしょう", "2 n", 1999799, ["connection", "relations"]),
    ])
    db = tmp_path / "defs.sqlite"
    b.build(str(dicts), str(db), str(tmp_path / "dict_styles.css"))
    rows = sqlite3.connect(db).execute(
        "SELECT reading, sense_ord, html FROM defs WHERE kind='je' ORDER BY sense_ord").fetchall()
    assert rows == [("こうしょう", 1, "negotiations; bargaining"),
                    ("こうしょう", 2, "connection; relations")]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --with anki pytest tests/test_build_def_index.py::test_build_indexes_jmdict_senses -v`
Expected: FAIL (no `je` rows; `rows == []`)

- [ ] **Step 3: Implement JMdict indexing**

Add to `tools/build_def_index.py` (above `build`):

```python
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
```

Then in `build`, after the J-J loop and before `CREATE INDEX`, add:

```python
    if _banks(dicts_dir, JE_DICT):
        total += _index_je(conn, dicts_dir)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --with anki pytest tests/test_build_def_index.py -v`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add tools/build_def_index.py tests/test_build_def_index.py
git commit -m "feat(enrich): build script JMdict sense indexing"
```

---

## Task 8: `build_def_index.py` — scoped `dict_styles.css`

**Files:**
- Modify: `tools/build_def_index.py`
- Test: `tests/test_build_def_index.py`

Only some dicts ship a `styles.css` (大辞泉 does). Each rule is scoped to `.aa-jj[data-dict="<id>"]` so it only styles that dict's block.

- [ ] **Step 1: Write the failing test**

Add to `tests/test_build_def_index.py`:

```python
def test_build_scopes_dict_styles(tmp_path):
    dicts = tmp_path / "dicts"
    _write_dict(dicts, "daijisen",
                [["猫", "ねこ", "", "", 0, [{"type": "structured-content", "content": "ねこ"}], 1, ""]],
                styles='[data-sc-content="extra"] { color: red; }')
    styles_out = tmp_path / "dict_styles.css"
    b.build(str(dicts), str(tmp_path / "defs.sqlite"), str(styles_out))
    css = styles_out.read_text(encoding="utf-8")
    assert '.aa-jj[data-dict="daijisen"] [data-sc-content="extra"]' in css
    assert "color: red" in css
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --with anki pytest tests/test_build_def_index.py::test_build_scopes_dict_styles -v`
Expected: FAIL with `FileNotFoundError` (styles file not written)

- [ ] **Step 3: Implement scoping**

Add to `tools/build_def_index.py` (above `build`):

```python
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
```

Then call it at the end of `build` (before `return total`):

```python
    _build_styles(dicts_dir, styles_path)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --with anki pytest tests/test_build_def_index.py -v`
Expected: PASS (3 tests)

- [ ] **Step 5: Build the real index from the user's dicts (manual verification)**

Run: `uv run --with anki python tools/build_def_index.py`
Expected: prints `indexed <N> J-J rows -> ankialign/user_files/defs.sqlite` (N in the hundreds of thousands); `ls -la ankialign/user_files/` shows `defs.sqlite` + `dict_styles.css`.

Add `ankialign/user_files/` to `.gitignore`:

```bash
echo "ankialign/user_files/" >> .gitignore
```

- [ ] **Step 6: Commit**

```bash
git add tools/build_def_index.py tests/test_build_def_index.py .gitignore
git commit -m "feat(enrich): scoped dict styles + real index build"
```

---

## Task 9: `enrich.py` — index lookup

**Files:**
- Create: `ankialign/enrich.py`
- Test: `tests/test_enrich.py`

- [ ] **Step 1: Write the failing test**

Create `tests/test_enrich.py`:

```python
import sqlite3
import pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankialign import enrich, config


def _make_db(path):
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE defs (term TEXT, reading TEXT, dict_id TEXT, "
                 "kind TEXT, sense_ord INTEGER, html TEXT)")
    conn.executemany("INSERT INTO defs VALUES (?,?,?,?,?,?)", [
        ("迷惑", "めいわく", "sankoku8", "jj", 0, "<b>めいわく</b>"),
        ("迷惑", "めいわく", "jmdict", "je", 1, "trouble; bother"),
        ("駐車場", "ちゅうしゃじょう", "jmdict", "je", 1, "parking lot"),
    ])
    conn.commit()
    conn.close()


def test_lookup_returns_jj_and_je(tmp_path):
    db = str(tmp_path / "defs.sqlite")
    _make_db(db)
    conn = enrich.open_index(db)
    jj, je = enrich.lookup(conn, "迷惑")
    assert jj == [{"term": "迷惑", "reading": "めいわく", "dict_id": "sankoku8",
                   "kind": "jj", "sense_ord": 0, "html": "<b>めいわく</b>"}]
    assert je[0]["html"] == "trouble; bother"
    assert enrich.lookup(conn, "駐車場") == ([], [{"term": "駐車場", "reading": "ちゅうしゃじょう",
        "dict_id": "jmdict", "kind": "je", "sense_ord": 1, "html": "parking lot"}])
    conn.close()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --with anki pytest tests/test_enrich.py::test_lookup_returns_jj_and_je -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'ankialign.enrich'`

- [ ] **Step 3: Implement**

Create `ankialign/enrich.py`:

```python
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --with anki pytest tests/test_enrich.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ankialign/enrich.py tests/test_enrich.py
git commit -m "feat(enrich): index lookup"
```

---

## Task 10: `enrich.py` — `ensure_fields` + `apply_dict_styles`

**Files:**
- Modify: `ankialign/enrich.py`
- Test: `tests/test_enrich.py`

`ensure_fields` adds the two template-unreferenced fields to the Kinomoto notetype (idempotent). `apply_dict_styles` appends the build's scoped `dict_styles.css` into the notetype CSS, marker-guarded (mirrors `template.py`).

- [ ] **Step 1: Write the failing test**

Add to `tests/test_enrich.py` (reuse the `col` fixture shape from `tests/test_convert.py` — import it):

```python
from tests.test_convert import _new_notetype, KINOMOTO_FIELDS


@pytest.fixture
def kcol(tmp_path):
    c = Collection(str(tmp_path / "k.anki2"))
    _new_notetype(c, config.TARGET_NOTETYPE, KINOMOTO_FIELDS)
    yield c
    c.close()


def test_ensure_fields_adds_and_is_idempotent(kcol):
    enrich.ensure_fields(kcol)
    names = [f["name"] for f in kcol.models.by_name(config.TARGET_NOTETYPE)["flds"]]
    assert config.ORIGINAL_MEANING_FIELD in names
    assert config.DEFINITION_SOURCE_FIELD in names
    n_before = len(names)
    enrich.ensure_fields(kcol)  # second call: no duplicate fields
    assert len(kcol.models.by_name(config.TARGET_NOTETYPE)["flds"]) == n_before


def test_apply_dict_styles_injects_once(kcol, tmp_path):
    css = tmp_path / "dict_styles.css"
    css.write_text('.aa-jj[data-dict="daijisen"] x { color: red }', encoding="utf-8")
    enrich.apply_dict_styles(kcol, str(css))
    enrich.apply_dict_styles(kcol, str(css))
    full = kcol.models.by_name(config.TARGET_NOTETYPE)["css"]
    assert full.count("AnkiAlign dict styles") == 1   # injected exactly once
    assert "color: red" in full
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --with anki pytest tests/test_enrich.py::test_ensure_fields_adds_and_is_idempotent -v`
Expected: FAIL with `AttributeError: module 'ankialign.enrich' has no attribute 'ensure_fields'`

- [ ] **Step 3: Implement**

Add to `ankialign/enrich.py`:

```python
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --with anki pytest tests/test_enrich.py -v`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add ankialign/enrich.py tests/test_enrich.py
git commit -m "feat(enrich): add fields + inject scoped dict styles"
```

---

## Task 11: `enrich.py` — `build_plan` (read-only dry run)

**Files:**
- Modify: `ankialign/enrich.py`
- Test: `tests/test_enrich.py`

`build_plan` classifies every in-scope Kinomoto note without mutating: `enrich` (has J-J), `english` (J-E only), `nohit` (neither). Returns counts + a few samples + the no-hit nids.

- [ ] **Step 1: Write the failing test**

Add to `tests/test_enrich.py`:

```python
def _add_kino(col, term, reading, meaning=""):
    m = col.models.by_name(config.TARGET_NOTETYPE)
    col.models.set_current(m)
    n = col.new_note(m)
    n["Term"] = term; n["Reading"] = reading; n["Meaning"] = meaning
    col.add_note(n, col.decks.id("言葉文章"))
    return n.id


def test_build_plan_classifies(kcol, tmp_path):
    db = str(tmp_path / "defs.sqlite"); _make_db(db)
    _add_kino(kcol, "迷惑", "めいわく")        # has J-J
    _add_kino(kcol, "駐車場", "ちゅうしゃじょう")  # english-only
    _add_kino(kcol, "謎語", "なぞご")           # no hit
    conn = enrich.open_index(db)
    plan = enrich.build_plan(kcol, conn)
    assert plan["counts"] == {"enrich": 1, "english": 1, "nohit": 1}
    assert len(plan["nohit_nids"]) == 1
    assert plan["samples"]                       # at least one before/after sample
    conn.close()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --with anki pytest tests/test_enrich.py::test_build_plan_classifies -v`
Expected: FAIL with `AttributeError: ... 'build_plan'`

- [ ] **Step 3: Implement**

Add to `ankialign/enrich.py`:

```python
def _compute(conn, term, reading):
    """Return (new_meaning, source, kind) for one note. kind in enrich/english/nohit."""
    jj_rows, je_rows = lookup(conn, term)
    jj = render_defs.choose_jj(jj_rows, reading, config.JJ_CHAIN)
    je = render_defs.numbered_glosses(je_rows, reading, config.GLOSS_CAP)
    meaning = render_defs.assemble_meaning(jj, je, english_open=not config.ENGLISH_COLLAPSED_DEFAULT)
    source = render_defs.source_label(jj, bool(je), config.DICT_LABELS)
    kind = "enrich" if jj else ("english" if je else "nohit")
    return meaning, source, kind


def _in_scope_nids(col):
    return col.find_notes(convert._search(config.TARGET_NOTETYPE))


def build_plan(col, conn):
    counts = {"enrich": 0, "english": 0, "nohit": 0}
    nohit_nids, samples, errors = [], [], []
    fld = {f["name"] for f in col.models.by_name(config.TARGET_NOTETYPE)["flds"]}
    for nid in _in_scope_nids(col):
        note = col.get_note(nid)
        term = note["Term"].strip() if "Term" in fld else ""
        if not term:
            continue
        reading = note["Reading"].strip() if "Reading" in fld else ""
        try:
            meaning, source, kind = _compute(conn, term, reading)
        except Exception as e:
            errors.append((nid, repr(e)))
            continue
        counts[kind] += 1
        if kind == "nohit":
            nohit_nids.append(nid)
        elif len(samples) < config.SAMPLE_N:
            samples.append((term, note["Meaning"], meaning, source))
    return {"counts": counts, "nohit_nids": nohit_nids,
            "samples": samples, "errors": errors}
```

Note: `convert._search` already builds `note:"jidoujisho Kinomoto" -deck:*Core2.3k* -deck:*文法*`.

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --with anki pytest tests/test_enrich.py -v`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add ankialign/enrich.py tests/test_enrich.py
git commit -m "feat(enrich): read-only build_plan classifier"
```

---

## Task 12: `enrich.py` — `enrich_notes` (the mutation)

**Files:**
- Modify: `ankialign/enrich.py`
- Test: `tests/test_enrich.py`

Mutation rules: stash original `Meaning` once (guarded by `ENRICHED_TAG`); set `Meaning` + `Definition Source`; tag; write only changed notes. No-hit notes: drop if fewer than `NO_HIT_DROP_THRESHOLD`, else tag `NO_DEF_TAG`.

- [ ] **Step 1: Write the failing test**

Add to `tests/test_enrich.py`:

```python
def test_enrich_notes_writes_stashes_and_is_idempotent(kcol, tmp_path):
    db = str(tmp_path / "defs.sqlite"); _make_db(db)
    enrich.ensure_fields(kcol)
    nid = _add_kino(kcol, "迷惑", "めいわく", meaning="OLD CLUTTER")
    conn = enrich.open_index(db)

    res = enrich.enrich_notes(kcol, conn)
    n = kcol.get_note(nid)
    assert n["Meaning"] == ('<div class="aa-jj" data-dict="sankoku8"><b>めいわく</b></div>'
                            '<details class="aa-je" open><summary>English</summary>'
                            '1. trouble; bother</details>')
    assert n[config.ORIGINAL_MEANING_FIELD] == "OLD CLUTTER"
    assert n[config.DEFINITION_SOURCE_FIELD] == "三省堂"
    assert config.ENRICHED_TAG in n.tags
    assert res["written"] == 1

    # idempotent: re-run writes nothing and does not re-stash
    res2 = enrich.enrich_notes(kcol, conn)
    assert res2["written"] == 0
    assert kcol.get_note(nid)[config.ORIGINAL_MEANING_FIELD] == "OLD CLUTTER"
    conn.close()


def test_enrich_notes_drops_few_nohit(kcol, tmp_path):
    db = str(tmp_path / "defs.sqlite"); _make_db(db)
    enrich.ensure_fields(kcol)
    keep = _add_kino(kcol, "迷惑", "めいわく")
    gone = _add_kino(kcol, "謎語", "なぞご")     # no hit, < threshold -> dropped
    conn = enrich.open_index(db)
    res = enrich.enrich_notes(kcol, conn)
    assert res["dropped"] == 1
    assert kcol.find_notes("nid:%d" % gone) == []
    assert kcol.find_notes("nid:%d" % keep) != []
    conn.close()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --with anki pytest tests/test_enrich.py::test_enrich_notes_writes_stashes_and_is_idempotent -v`
Expected: FAIL with `AttributeError: ... 'enrich_notes'`

- [ ] **Step 3: Implement**

Add to `ankialign/enrich.py`:

```python
def enrich_notes(col, conn):
    """Rewrite Meaning for in-scope Kinomoto notes. Returns counts. Idempotent.

    Caller is responsible for the backup + schema acknowledgement (the preview
    dialog does this). ensure_fields/apply_dict_styles are called here so the
    function is self-contained for headless use.
    """
    ensure_fields(col)
    apply_dict_styles(col)
    written = 0
    nohit_nids = []
    for nid in _in_scope_nids(col):
        note = col.get_note(nid)
        term = note["Term"].strip()
        if not term:
            continue
        meaning, source, kind = _compute(conn, term, note["Reading"].strip())
        if kind == "nohit":
            nohit_nids.append(nid)
            continue
        # stash the true original exactly once
        if config.ENRICHED_TAG not in note.tags:
            note[config.ORIGINAL_MEANING_FIELD] = note["Meaning"]
        unchanged = (note["Meaning"] == meaning
                     and note[config.DEFINITION_SOURCE_FIELD] == source
                     and config.ENRICHED_TAG in note.tags)
        if unchanged:
            continue
        note["Meaning"] = meaning
        note[config.DEFINITION_SOURCE_FIELD] = source
        if config.ENRICHED_TAG not in note.tags:
            note.add_tag(config.ENRICHED_TAG)
        col.update_note(note)
        written += 1

    dropped = 0
    if nohit_nids and len(nohit_nids) < config.NO_HIT_DROP_THRESHOLD:
        col.remove_notes(nohit_nids)
        dropped = len(nohit_nids)
    else:
        for nid in nohit_nids:
            note = col.get_note(nid)
            if config.NO_DEF_TAG not in note.tags:
                note.add_tag(config.NO_DEF_TAG)
                col.update_note(note)
    return {"written": written, "dropped": dropped,
            "nohit_tagged": 0 if dropped else len(nohit_nids)}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --with anki pytest tests/test_enrich.py -v`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add ankialign/enrich.py tests/test_enrich.py
git commit -m "feat(enrich): enrich_notes mutation (stash/tag/drop, idempotent)"
```

---

## Task 13: card CSS — `.aa-jj`/`.aa-je` styling (layer 2)

**Files:**
- Modify: `ankialign/kinomoto.css`
- Test: `tests/test_template.py`

These rules are committed (static); the build's scoped dict styles (layer 1) are injected separately by `apply_dict_styles`.

- [ ] **Step 1: Write the failing test**

Add to `tests/test_template.py`:

```python
def test_kinomoto_css_has_definition_styles():
    import os
    from ankialign import template
    css = template._css()
    assert ".aa-jj" in css and ".aa-je" in css
    assert ".aa-jj ruby rt" in css            # furigana polish present
    assert ".aa-je > summary" in css          # English toggle styling present
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --with anki pytest tests/test_template.py::test_kinomoto_css_has_definition_styles -v`
Expected: FAIL with `AssertionError`

- [ ] **Step 3: Append the styling**

Append to `ankialign/kinomoto.css`:

```css

/* ---- AnkiAlign definition blocks (enrichment) ---- */
.aa-jj { text-align: left; font-family: "Hiragino Mincho ProN", "Noto Serif CJK JP", serif;
  font-size: 15px; line-height: 1.8; }
.aa-jj ruby rt { font-size: .55em; opacity: .85; font-weight: 400; }
.aa-jj ul, .aa-jj ol { margin: .25em 0; padding-left: 1.3em; }
.aa-jj li { margin: .1em 0; }
.aa-jj a { color: inherit; text-decoration: none; border-bottom: 1px dotted rgba(0,0,0,.18); }
.nightMode .aa-jj a { border-bottom-color: rgba(255,255,255,.25); }
.aa-je { text-align: left; margin-top: 11px; padding-top: 8px;
  border-top: 1px solid rgba(0,0,0,.12); font-size: 13.5px; line-height: 1.6; color: #555; }
.nightMode .aa-je { color: #bbb; border-top-color: rgba(255,255,255,.15); }
.aa-je > summary { cursor: pointer; font-size: 11px; letter-spacing: .06em;
  text-transform: uppercase; opacity: .55; list-style: none; }
.aa-je > summary::-webkit-details-marker { display: none; }
.aa-je[open] > summary { margin-bottom: 4px; }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --with anki pytest tests/test_template.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ankialign/kinomoto.css tests/test_template.py
git commit -m "feat(enrich): card CSS for definition blocks"
```

---

## Task 14: `enrich_preview.py` — the dialog

**Files:**
- Create: `ankialign/enrich_preview.py`

This is GUI glue (Qt); its logic (`build_plan`, `enrich_notes`) is already tested headless. No new unit test — verified manually in Anki (Step 4). Mirrors `ankialign/preview.py`: backup synchronously, acknowledge the schema change on the main thread, run under `mw.progress`, then `mw.reset()`.

- [ ] **Step 1: Implement the dialog**

Create `ankialign/enrich_preview.py`:

```python
"""Dry-run dialog for definition enrichment; runs the pass on confirm."""
import os
from aqt import mw
from aqt.qt import QDialog, QVBoxLayout, QLabel, QTextEdit, QPushButton, QHBoxLayout
from aqt.utils import showInfo
from anki.errors import AbortSchemaModification
from . import convert, enrich, config


def _plan_text(plan: dict) -> str:
    c = plan["counts"]
    lines = [f"Enrich (J-J): {c['enrich']}", f"English-only: {c['english']}",
             f"No definition: {c['nohit']}"]
    if c["nohit"]:
        verb = ("will be DROPPED" if c["nohit"] < config.NO_HIT_DROP_THRESHOLD
                else f"will be tagged {config.NO_DEF_TAG}")
        lines.append(f"   ({c['nohit']} no-hit cards {verb})")
    lines.append("")
    for term, old, new, source in plan["samples"][:config.SAMPLE_N]:
        lines.append(f"• {term}  [{source}]")
    if plan["errors"]:
        lines.append(f"\n⚠ {len(plan['errors'])} errors (skipped):")
        for nid, err in plan["errors"][:10]:
            lines.append(f"   nid {nid}: {err}")
    return "\n".join(lines)


def show() -> None:
    if not os.path.exists(config.DEF_INDEX_PATH):
        showInfo("No dictionary index found.\n\nBuild it first:\n"
                 "uv run --with anki python tools/build_def_index.py")
        return
    conn = enrich.open_index()
    plan = enrich.build_plan(mw.col, conn)

    dlg = QDialog(mw)
    dlg.setWindowTitle("AnkiAlign — Enrich definitions (preview)")
    lay = QVBoxLayout(dlg)
    lay.addWidget(QLabel("Dry run — nothing has changed yet:"))
    box = QTextEdit(); box.setReadOnly(True); box.setPlainText(_plan_text(plan))
    lay.addWidget(box)
    btns = QHBoxLayout()
    cancel = QPushButton("Cancel"); run = QPushButton("Backup && Enrich")
    btns.addWidget(cancel); btns.addWidget(run); lay.addLayout(btns)
    cancel.clicked.connect(dlg.reject)

    def do_run():
        dlg.accept()
        backup_dir = mw.pm.backupFolder()
        convert.backup_collection(mw.col, backup_dir)
        try:
            mw.col.mod_schema(check=True)   # main-thread schema ack (adds fields)
        except AbortSchemaModification:
            conn.close(); showInfo("Cancelled — schema change not accepted."); return
        mw.progress.start(label="AnkiAlign: enriching definitions…", immediate=True)
        try:
            res = enrich.enrich_notes(mw.col, conn)
        finally:
            mw.progress.finish(); conn.close()
        mw.reset()
        showInfo(f"Enriched (written): {res['written']}\n"
                 f"Dropped (no def): {res['dropped']}\n"
                 f"Tagged no-def: {res['nohit_tagged']}\n\n"
                 f"Rollback: restore the backup at\n{backup_dir}")

    run.clicked.connect(do_run)
    dlg.exec()
```

- [ ] **Step 2: Verify it imports headlessly**

Run: `uv run --with anki python -c "import ast; ast.parse(open('ankialign/enrich_preview.py').read()); print('ok')"`
Expected: `ok` (syntax valid; the module is only imported inside Anki where `aqt` exists)

- [ ] **Step 3: Commit**

```bash
git add ankialign/enrich_preview.py
git commit -m "feat(enrich): preview dialog"
```

- [ ] **Step 4: Manual verification (after Task 15 wires the menu)** — deferred to Task 15 Step 4.

---

## Task 15: wire the Tools-menu action

**Files:**
- Modify: `ankialign/__init__.py`

- [ ] **Step 1: Add the second action**

In `ankialign/__init__.py`, inside the `try` block, add a second import and action next to the existing one:

```python
    from . import preview, enrich_preview

    def _add_menu():
        a1 = QAction("AnkiAlign: Unify note types…", mw)
        a1.triggered.connect(preview.show)
        mw.form.menuTools.addAction(a1)
        a2 = QAction("AnkiAlign: Enrich definitions…", mw)
        a2.triggered.connect(enrich_preview.show)
        mw.form.menuTools.addAction(a2)
```

Replace the existing `from . import preview` line and `_add_menu` body with the above (keep the surrounding `try/except ModuleNotFoundError` exactly as is).

- [ ] **Step 2: Verify the package imports headlessly**

Run: `uv run --with anki python -c "import ankialign; print('ok')"`
Expected: `ok` (the `aqt` ModuleNotFoundError is swallowed by the existing guard)

- [ ] **Step 3: Run the full test suite**

Run: `uv run --with anki pytest -q`
Expected: all tests pass (existing + the new render_defs/enrich/build tests).

- [ ] **Step 4: Manual smoke test in Anki**

1. Symlink/copy the add-on into Anki's addons folder if not already (the dev setup the user uses).
2. Ensure `ankialign/user_files/defs.sqlite` exists (Task 8 Step 5).
3. Launch Anki → Tools → **AnkiAlign: Enrich definitions…** → confirm the dry-run counts look sane (≈8.1k enrich, ≈80 english-only, 0 no-hit) → **Backup && Enrich**.
4. Open the browser, pick a card (e.g. `迷惑`), confirm `Meaning` shows the J-J block + numbered English, `Definition Source` = `三省堂`, `Original Meaning` holds the old value, tag `ankialign::enriched` present.
5. Re-run the menu action → result reports `written: 0` (idempotent).

- [ ] **Step 5: Commit**

```bash
git add ankialign/__init__.py
git commit -m "feat(enrich): add Enrich definitions menu action"
```

---

## Task 16: 広辞苑→三省堂 resemblance CSS (layer 3)

**Files:**
- Modify: `ankialign/kinomoto.css`

This is **visual/iterative**, not unit-testable: it tunes 広辞苑's native `data-name` spans to
read like 三省堂. Scoped to `.aa-jj[data-dict="koujien7"]`, so it only affects enriched 広辞苑
blocks (never a jidoujisho card). Develop it against the existing mockup harness.

- [ ] **Step 1: Inspect 広辞苑's structure vs 三省堂's**

Run, to list the `data-name`/`data-sc-content` attributes 広辞苑 uses (so the CSS targets real hooks):

```bash
cd ~/projects/anki-align/research/dict-compare && uv run python - <<'PY'
import json, glob, re, collections
for d in ("koujien7", "sankoku8"):
    names = collections.Counter()
    for tb in glob.glob(f"dicts/{d}/term_bank_*.json")[:1]:
        raw = open(tb, encoding="utf-8").read()
        for m in re.findall(r'"(?:name|content)":\s*"([^"]+)"', raw):
            names[m] += 1
    print(d, names.most_common(20))
PY
```

Expected: prints the most common span names for each dict (e.g. 広辞苑 sense/example/reading-annotation hooks) — use these as the selectors below.

- [ ] **Step 2: Append the resemblance CSS**

Append to `ankialign/kinomoto.css` (tune the selectors to Step 1's output; this is the starting block):

```css

/* ---- AnkiAlign 広辞苑 → 三省堂 resemblance (layer 3) ---- */
/* match 三省堂's body size/rhythm and tame 広辞苑's tiny superscript annotations */
.aa-jj[data-dict="koujien7"] { font-size: 15px; line-height: 1.8; }
.aa-jj[data-dict="koujien7"] [style*="font-size"] { font-size: .82em !important; }
.aa-jj[data-dict="koujien7"] [data-name="entry-index"],
.aa-jj[data-dict="koujien7"] [data-sc-content="reading"] { font-size: .7em; opacity: .6; }
/* sense markers (①②…) spacing to match 三省堂's 《》 cadence */
.aa-jj[data-dict="koujien7"] [data-name="sense-number"] { margin: 0 .15em 0 .3em; font-weight: 600; }
```

- [ ] **Step 3: Verify visually against the mockup**

Run: `cd ~/projects/anki-align/research/dict-compare && uv run --with anki python mockup_enriched.py`
Then open `http://localhost:8732/mockup.html` and compare the 広辞苑 card against the 三省堂 card.
Adjust the selectors/values from Step 2 until the 広辞苑 block reads consistently (same body size,
no microscopic text, aligned sense markers). The mockup already scopes in each dict's `styles.css`,
so this iterates the same layering the card uses.

Note: `mockup_enriched.py`'s `AA_CSS` is the prototype of `kinomoto.css`'s layer-2 block; keep the
two in sync while tuning so the mockup reflects the shipped CSS.

- [ ] **Step 4: Run the template test + commit**

Run: `uv run --with anki pytest tests/test_template.py -v`
Expected: PASS (layer-2 assertions still hold; layer 3 is additive).

```bash
git add ankialign/kinomoto.css
git commit -m "feat(enrich): 広辞苑→三省堂 resemblance CSS"
```

---

## Self-review (completed by plan author)

**Spec coverage:**
- §1 dicts/chain/JMdict cap → Task 1 (config), Task 4 (chain), Task 3 (cap). ✓
- §2 two-phase, SQLite schema, render_sc, native styles.css extraction → Tasks 2, 6, 7, 8. ✓
- §3 lookup + reading disambiguation + (simplified) katakana note → Task 4, Task 9. The spec's "prefer JMdict-aligned" guard is intentionally simplified to first-reading-match: in the cited ヤギ case the dict holds only the homograph entry, so the guard cannot help; katakana homographs are accepted as a rare known limitation, auditable via `Definition Source`. (Recorded here so it is not mistaken for a gap.)
- §4 assembled Meaning + CSS layers → Task 5 (assemble, with English `<details>` toggle), Task 13 (layer 2 static + `<details>/<summary>` styling), Task 8/10 (layer 1 scoped dict styles), Task 16 (layer 3 広辞苑→三省堂 resemblance). English-collapse default via `config.ENGLISH_COLLAPSED_DEFAULT` (Task 1), consumed in `_compute` (Task 11). ✓
- §5 fields, scope, overwrite, stash-once, no-hit drop/tag → Task 10 (fields), Task 11/12 (scope + mutation). ✓
- §6 safety: backup, dry-run dialog, missing-DB, per-note errors → Task 14 (dialog), Task 11 (errors). ✓
- §7 tests → Tasks 2–13 each ship tests; build smoke test Task 6–8. ✓

**Placeholder scan:** no TBD/TODO; every code step contains complete code.

**Type consistency:** row dict keys (`term/reading/dict_id/kind/sense_ord/html`) are identical across `build_def_index` inserts, `enrich.lookup`, and `render_defs` consumers. `choose_jj` returns `(dict_id, html)`; `assemble_meaning`/`source_label` consume exactly that shape. `_compute` returns `(meaning, source, kind)` consumed identically in `build_plan` and `enrich_notes`.
