# Unify Note Types — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an in-process Anki add-on that converts the in-scope vocab notes of MyNote / jidoujisho Yuuna / Kanji Study Word Model v3 onto the existing **jidoujisho Kinomoto** note type, preserving all card review history, non-destructively.

**Architecture:** Pure string-transform modules (`markup`, `mapping`, `config`) with zero Anki imports → fully unit-testable. An engine (`convert`) snapshots each note's source fields, calls `Collection.models.change()` to switch note type + template (preserving history), then repopulates Kinomoto fields from the snapshot — all inside one undoable op after a forced backup. A read-only preview dialog runs the mappings dry first. A `template` step applies the front-markup fix.

**Tech Stack:** Python 3, `anki`/`aqt` (Anki add-on APIs), `pytest`, `uv`. Pure modules avoid Anki imports; the engine and integration tests use the `anki` package headless.

Spec: `docs/superpowers/specs/2026-06-04-unify-note-types-design.md`

---

## File structure

```
ankialign/                 # the add-on package (symlinked into Anki for manual runs)
  __init__.py              # registers Tools menu action (aqt) — thin
  config.py                # constants + in_scope() predicate (pure)
  markup.py                # jrp / Anki-furigana string transforms (pure)
  mapping.py               # per-source dict->dict field mappers (pure)
  convert.py               # engine: select / build_plan / run_conversion (anki)
  preview.py               # read-only dry-run dialog (aqt) — thin
  template.py              # apply Kinomoto front-markup fix + goal-#4 css (anki)
tests/
  test_config.py
  test_markup.py
  test_mapping.py
  test_convert.py          # integration: synthetic anki.Collection
pyproject.toml             # uv project; dev deps pytest + anki
```

Responsibility split: `markup` knows nothing about Anki or note types; `mapping` knows field names but not Anki; `convert` orchestrates Anki; `preview`/`__init__` are thin GUI shells over `convert`. Files that change together (the three pure transform modules) live together but stay separate by responsibility.

---

## Task 1: Project scaffolding

**Files:**
- Create: `pyproject.toml`
- Create: `ankialign/__init__.py` (empty placeholder for now)
- Create: `tests/__init__.py` (empty)

- [ ] **Step 1: Write `pyproject.toml`**

```toml
[project]
name = "ankialign"
version = "0.1.0"
requires-python = ">=3.11"

[dependency-groups]
dev = ["pytest>=8", "anki>=24.11"]

[tool.pytest.ini_options]
testpaths = ["tests"]
```

- [ ] **Step 2: Create empty `ankialign/__init__.py` and `tests/__init__.py`**

```bash
mkdir -p ankialign tests
: > ankialign/__init__.py
: > tests/__init__.py
```

- [ ] **Step 3: Sync the environment**

Run: `uv sync`
Expected: creates `.venv`, installs pytest + anki. (anki pulls protobuf etc. — may take a minute.)

- [ ] **Step 4: Verify pytest runs (no tests yet)**

Run: `uv run pytest -q`
Expected: `no tests ran` (exit 5) — confirms pytest works.

- [ ] **Step 5: Add `.venv/` and `uv.lock` handling to .gitignore, then commit**

```bash
printf '\n.venv/\n' >> .gitignore
git add pyproject.toml ankialign/__init__.py tests/__init__.py .gitignore uv.lock
git commit -m "chore: scaffold ankialign add-on package + uv project"
```

---

## Task 2: `config.py` — scope constants + predicate

**Files:**
- Create: `ankialign/config.py`
- Test: `tests/test_config.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_config.py
from ankialign import config

def test_in_scope_excludes_core_and_grammar():
    assert config.in_scope("言葉文章") is True
    assert config.in_scope("言葉::文章") is True
    assert config.in_scope("言葉Core2.3k") is False
    assert config.in_scope("言葉::Core2.3k") is False
    assert config.in_scope("文法") is False
    assert config.in_scope("文法::助詞") is False

def test_known_constants():
    assert config.TARGET_NOTETYPE == "jidoujisho Kinomoto"
    assert "MyNote" in config.SOURCE_NOTETYPES
    assert "jidoujisho Yuuna" in config.SOURCE_NOTETYPES
    assert "Kanji Study Word Model v3" in config.SOURCE_NOTETYPES
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run pytest tests/test_config.py -v`
Expected: FAIL — `ModuleNotFoundError: ankialign.config`.

- [ ] **Step 3: Write `ankialign/config.py`**

```python
"""Static configuration for the unify pass (single-user tool)."""

TARGET_NOTETYPE = "jidoujisho Kinomoto"

SOURCE_NOTETYPES = [
    "MyNote",
    "jidoujisho Yuuna",
    "Kanji Study Word Model v3",
]

# A note is in scope unless any of its decks' names contain one of these.
EXCLUDE_DECK_SUBSTRINGS = ["Core2.3k", "文法"]

# How many before/after samples per source to show in the preview dialog.
SAMPLE_N = 5


def in_scope(deck_name: str) -> bool:
    """True if a deck name is an in-scope mined deck (not Core2.3k / 文法)."""
    return not any(sub in deck_name for sub in EXCLUDE_DECK_SUBSTRINGS)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run pytest tests/test_config.py -v`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add ankialign/config.py tests/test_config.py
git commit -m "feat: add config constants and in_scope predicate"
```

---

## Task 3: `markup.py` — jrp / furigana transforms

These are the derivations the spec needs: jidoujisho/JRP markup → surface text / kana reading / Anki ` 漢字[かな]` notation, plus the Anki-notation split used for Kanji Study's `EntryFurigana`.

**Files:**
- Create: `ankialign/markup.py`
- Test: `tests/test_markup.py`

- [ ] **Step 1: Write the failing test** (cases are real values pulled from the collection)

```python
# tests/test_markup.py
from ankialign import markup

def test_jrp_surface():
    assert markup.jrp_surface("{[異変|いへん];0}") == "異変"
    assert markup.jrp_surface("{[一|ひと]つ;2}") == "一つ"
    assert markup.jrp_surface("{[気付|きづ][いた=く];Y2}") == "気付いた"
    assert markup.jrp_surface("{くださ[い=る];Y3}") == "ください"
    assert markup.jrp_surface("{それ;1}") == "それ"
    assert markup.jrp_surface("一") == "一"          # plain kanji
    assert markup.jrp_surface("それ") == "それ"        # plain kana

def test_jrp_to_furigana():
    assert markup.jrp_to_furigana("{[異変|いへん];0}") == "異変[いへん]"
    assert markup.jrp_to_furigana("{[一|ひと]つ;2}") == "一[ひと]つ"
    assert markup.jrp_to_furigana("{[気付|きづ][いた=く];Y2}") == "気付[きづ]いた"
    assert markup.jrp_to_furigana("一") == "一"

def test_jrp_reading():
    assert markup.jrp_reading("{[異変|いへん];0}") == "いへん"
    assert markup.jrp_reading("{[一|ひと]つ;2}") == "ひとつ"
    assert markup.jrp_reading("{[気付|きづ][いた=く];Y2}") == "きづいた"
    assert markup.jrp_reading("それ") == "それ"        # kana reads as itself
    assert markup.jrp_reading("一") == ""              # bare kanji -> no clean reading

def test_entryfurigana_split():
    assert markup.entryfurigana_surface("有[あ]りそう") == "有りそう"
    assert markup.entryfurigana_reading("有[あ]りそう") == "ありそう"
    assert markup.entryfurigana_surface("気[き] 合[あい]") == "気合"
    assert markup.entryfurigana_reading("気[き] 合[あい]") == "きあい"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run pytest tests/test_markup.py -v`
Expected: FAIL — `ModuleNotFoundError: ankialign.markup`.

- [ ] **Step 3: Write `ankialign/markup.py`**

```python
"""Pure string transforms for Japanese furigana / jidoujisho-JRP markup.

No Anki imports — unit-testable standalone.
"""
import re

# matches an Anki furigana segment: base[reading]
_RUBY = re.compile(r"([^\s\[\]<>]+)\[([^\]]+)\]")
_HAS_KANJI = re.compile(r"[㐀-鿿豈-﫿々]")


def _anki_surface(s: str) -> str:
    """Anki furigana notation -> surface (drop readings, join segments)."""
    return _RUBY.sub(r"\1", s).replace(" ", "")


def _anki_reading(s: str) -> str:
    """Anki furigana notation -> reading (readings replace their bases)."""
    return _RUBY.sub(r"\2", s).replace(" ", "")


def jrp_to_furigana(s: str) -> str:
    """jidoujisho/JRP markup -> Anki furigana notation, e.g.
    '{[一|ひと]つ;2}' -> '一[ひと]つ'."""
    def _unit(m: re.Match) -> str:
        return re.sub(r";[^;]*$", "", m.group(1))            # drop trailing ;accent
    s = re.sub(r"\{([^{}]*)\}", _unit, s)
    s = re.sub(r"\[([^\[\]|=]*)=([^\[\]]*)\]", r"\1", s)       # [surface=base] -> surface
    s = re.sub(r"\[([^\[\]|]+)\|([^\[\]]+)\]", r" \1[\2]", s)  # [漢字|かな] -> ' 漢字[かな]'
    return s.strip()


def jrp_surface(s: str) -> str:
    return _anki_surface(jrp_to_furigana(s))


def jrp_reading(s: str) -> str:
    """Kana reading, or '' when bare kanji remains (no clean reading available)."""
    r = _anki_reading(jrp_to_furigana(s))
    return "" if _HAS_KANJI.search(r) else r


def entryfurigana_surface(s: str) -> str:
    return _anki_surface(s)


def entryfurigana_reading(s: str) -> str:
    r = _anki_reading(s)
    return "" if _HAS_KANJI.search(r) else r
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run pytest tests/test_markup.py -v`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add ankialign/markup.py tests/test_markup.py
git commit -m "feat: add jrp/furigana markup transforms with tests"
```

---

## Task 4: `markup.py` ground-truth check against real data (optional, local)

Verifies the parser against the collection's own `Reading`/`Furigana` fields. Skips automatically when `collection.anki2` isn't present (so it never breaks CI).

**Files:**
- Test: `tests/test_markup_groundtruth.py`

- [ ] **Step 1: Write the data-driven test**

```python
# tests/test_markup_groundtruth.py
import os, sqlite3
import pytest
from ankialign import markup

DB = os.path.join(os.path.dirname(__file__), "..", "collection.anki2")
FS = "\x1f"

pytestmark = pytest.mark.skipif(not os.path.exists(DB), reason="no collection.anki2")


def _con():
    con = sqlite3.connect(f"file:{DB}?immutable=1", uri=True)
    con.create_collation(
        "unicase",
        lambda a, b: (a.casefold() > b.casefold()) - (a.casefold() < b.casefold()),
    )
    return con


def test_jrp_reading_matches_yuuna_reading_field():
    """For Yuuna notes whose Term carries jrp markup, derived reading should
    match the stored Reading field at a high rate."""
    con = _con()
    mid = con.execute(
        "SELECT id FROM notetypes WHERE name='jidoujisho Yuuna'"
    ).fetchone()[0]
    names = [r[0] for r in con.execute(
        "SELECT name FROM fields WHERE ntid=? ORDER BY ord", (mid,))]
    idx = {n: i for i, n in enumerate(names)}
    total = match = 0
    for (flds,) in con.execute("SELECT flds FROM notes WHERE mid=?", (mid,)):
        f = flds.split(FS)
        term, reading = f[idx["Term"]], f[idx["Reading"]]
        if "{" not in term or not reading.strip():
            continue
        total += 1
        if markup.jrp_reading(term) == reading.strip():
            match += 1
    assert total > 0
    assert match / total >= 0.95, f"{match}/{total} readings matched"
```

- [ ] **Step 2: Run it**

Run: `uv run pytest tests/test_markup_groundtruth.py -v`
Expected: PASS locally (≥95% match); SKIPPED where the collection is absent.

- [ ] **Step 3: Commit**

```bash
git add tests/test_markup_groundtruth.py
git commit -m "test: ground-truth markup parser against Yuuna Reading field"
```

---

## Task 5: `mapping.py` — per-source field mappers

**Files:**
- Create: `ankialign/mapping.py`
- Test: `tests/test_mapping.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_mapping.py
from ankialign import mapping

def test_map_mynote_derives_term_and_renames():
    src = {
        "Sentence": "それを{[一|ひと]つ;2}{くださ[い=る];Y3}。",
        "Translation": "Please give me one of those.",
        "Target Word": "{[一|ひと]つ;2}",
        "Definitions": "<i>(adj-no)</i> one",
        "Screenshot": "",
        "Sentence Audio": "",
        "Word Audio": "[sound:w.mp3]",
        "Images": "<img src='i.jpg'>",
        "Example Sentences": "x<br>y",
        "Frequency": "1234",
        "Pitch": "<svg>...</svg>",
    }
    out = mapping.map_mynote(src)
    assert out["Term"] == "一つ"
    assert out["Reading"] == "ひとつ"
    assert out["Furigana"] == "一[ひと]つ"
    assert out["Sentence"] == src["Sentence"]
    assert out["Meaning"] == "<i>(adj-no)</i> one"
    assert out["Term Audio"] == "[sound:w.mp3]"
    assert out["Image"] == "<img src='i.jpg'>"   # Screenshot empty -> Images
    assert out["Pitch Accent"] == "<svg>...</svg>"
    assert out["Frequency"] == "1234"
    assert "Translation" not in out and "Example Sentences" not in out

def test_map_mynote_prefers_screenshot_for_image():
    src = {"Target Word": "一", "Screenshot": "<img src='s.jpg'>", "Images": "<img src='i.jpg'>"}
    assert mapping.map_mynote(src)["Image"] == "<img src='s.jpg'>"

def test_map_yuuna_cleans_term_keeps_rest():
    src = {"Term": "{[土砂|どしゃ];1}", "Reading": "どしゃ", "Furigana": "土砂[どしゃ]",
           "Sentence": "", "Meaning": "earth and sand", "Notes": "",
           "Image": "", "Term Audio": "[sound:y.mp3]", "Pitch Accent": "ど<span>しゃ</span>",
           "Expanded Meaning": "", "Collapsed Meaning": "", "Sentence Audio": ""}
    out = mapping.map_yuuna(src)
    assert out["Term"] == "土砂"
    assert out["Reading"] == "どしゃ"
    assert out["Meaning"] == "earth and sand"
    assert out["Term Audio"] == "[sound:y.mp3]"

def test_map_kanjistudy_core_only():
    src = {"ID": "x", "Entry": "有[あ,ある;k1]り そう[;h,a]", "EntryFurigana": "有[あ]りそう",
           "Meaning": "probable, likely", "Sentence": "", "Translation": "t",
           "Screenshot": "s", "Word Audio": "[sound:k.mp3]", "Sentence Audio": "sa",
           "Images": "im", "Example Sentences": "ex", "Frequency": "99"}
    out = mapping.map_kanjistudy(src)
    assert out == {
        "Term": "有りそう", "Reading": "ありそう", "Furigana": "有[あ]りそう",
        "Meaning": "probable, likely", "Term Audio": "[sound:k.mp3]",
    }

def test_mappers_registry():
    assert set(mapping.MAPPERS) == {
        "MyNote", "jidoujisho Yuuna", "Kanji Study Word Model v3"}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run pytest tests/test_mapping.py -v`
Expected: FAIL — `ModuleNotFoundError: ankialign.mapping`.

- [ ] **Step 3: Write `ankialign/mapping.py`**

```python
"""Per-source field mappers: source-field dict -> Kinomoto-field dict.

Pure (only depends on markup). Keys present in the result are written to the
converted note; everything else on Kinomoto stays blank. Source fields not
referenced here are dropped.
"""
from . import markup


def _first_nonempty(*vals: str) -> str:
    for v in vals:
        if v and v.strip():
            return v
    return ""


def map_mynote(s: dict) -> dict:
    tw = s.get("Target Word", "")
    return {
        "Term": markup.jrp_surface(tw),
        "Reading": markup.jrp_reading(tw),
        "Furigana": markup.jrp_to_furigana(tw),
        "Sentence": s.get("Sentence", ""),
        "Meaning": s.get("Definitions", ""),
        "Pitch Accent": s.get("Pitch", ""),
        "Image": _first_nonempty(s.get("Screenshot", ""), s.get("Images", "")),
        "Term Audio": s.get("Word Audio", ""),
        "Sentence Audio": s.get("Sentence Audio", ""),
        "Frequency": s.get("Frequency", ""),
    }


def map_yuuna(s: dict) -> dict:
    return {
        "Term": markup.jrp_surface(s.get("Term", "")),
        "Reading": s.get("Reading", ""),
        "Furigana": s.get("Furigana", ""),
        "Sentence": s.get("Sentence", ""),
        "Meaning": s.get("Meaning", ""),
        "Notes": s.get("Notes", ""),
        "Image": s.get("Image", ""),
        "Term Audio": s.get("Term Audio", ""),
        "Pitch Accent": s.get("Pitch Accent", ""),
        "Expanded Meaning": s.get("Expanded Meaning", ""),
        "Collapsed Meaning": s.get("Collapsed Meaning", ""),
        "Sentence Audio": s.get("Sentence Audio", ""),
    }


def map_kanjistudy(s: dict) -> dict:
    ef = s.get("EntryFurigana", "")
    return {
        "Term": markup.entryfurigana_surface(ef),
        "Reading": markup.entryfurigana_reading(ef),
        "Furigana": ef,
        "Meaning": s.get("Meaning", ""),
        "Term Audio": s.get("Word Audio", ""),
    }


MAPPERS = {
    "MyNote": map_mynote,
    "jidoujisho Yuuna": map_yuuna,
    "Kanji Study Word Model v3": map_kanjistudy,
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run pytest tests/test_mapping.py -v`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add ankialign/mapping.py tests/test_mapping.py
git commit -m "feat: add per-source field mappers with tests"
```

---

## Task 6: `convert.py` — selection + dry-run plan

**Files:**
- Create: `ankialign/convert.py`
- Test: `tests/test_convert.py` (synthetic `anki.Collection` fixture)

- [ ] **Step 1: Write the integration test for selection + plan**

```python
# tests/test_convert.py
import pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankialign import convert, config


def _new_notetype(col, name, fields):
    m = col.models.new(name)
    for fname in fields:
        col.models.add_field(m, col.models.new_field(fname))
    t = col.models.new_template("Card 1")
    t["qfmt"] = "{{%s}}" % fields[0]
    t["afmt"] = "{{FrontSide}}<hr>{{%s}}" % fields[1] if len(fields) > 1 else "{{FrontSide}}"
    col.models.add_template(m, t)
    col.models.add(m)
    return col.models.by_name(name)


KINOMOTO_FIELDS = ["Term", "Reading", "Furigana", "Sentence", "SentenceFurigana",
    "Cloze Before", "Cloze Inside", "Cloze After", "Meaning", "Expanded Meaning",
    "Collapsed Meaning", "Notes", "Context", "Frequency", "Pitch Accent", "Image",
    "Term Audio", "Sentence Audio"]
MYNOTE_FIELDS = ["Sentence", "Translation", "Target Word", "Definitions", "Screenshot",
    "Sentence Audio", "Word Audio", "Images", "Example Sentences", "Frequency", "Pitch"]


@pytest.fixture
def col(tmp_path):
    c = Collection(str(tmp_path / "test.anki2"))
    _new_notetype(c, config.TARGET_NOTETYPE, KINOMOTO_FIELDS)
    _new_notetype(c, "MyNote", MYNOTE_FIELDS)
    mined = c.decks.id("言葉文章")
    core = c.decks.id("言葉Core2.3k")
    mynote = c.models.by_name("MyNote")
    c.models.set_current(mynote)
    # in-scope note
    n1 = c.new_note(mynote)
    n1["Target Word"] = "{[一|ひと]つ;2}"; n1["Sentence"] = "一つください。"
    n1["Definitions"] = "one"; n1["Word Audio"] = "[sound:a.mp3]"
    c.add_note(n1, mined)
    # excluded (Core) note
    n2 = c.new_note(mynote)
    n2["Target Word"] = "二"; n2["Sentence"] = "二です。"
    c.add_note(n2, core)
    yield c
    c.close()


def test_select_notes_excludes_core(col):
    sel = convert.select_notes(col)
    assert len(sel["MyNote"]) == 1   # the Core note is excluded


def test_build_plan_is_readonly_and_maps(col):
    before = col.models.by_name("MyNote")["id"]
    plan = convert.build_plan(col)
    # nothing mutated
    n = col.get_note(convert.select_notes(col)["MyNote"][0])
    assert n.note_type()["id"] == before
    # plan content
    assert plan["sources"]["MyNote"]["count"] == 1
    src, kino = plan["sources"]["MyNote"]["samples"][0]
    assert kino["Term"] == "一つ" and kino["Reading"] == "ひとつ"
    assert plan["errors"] == []
```

- [ ] **Step 2: Run to verify it fails**

Run: `uv run pytest tests/test_convert.py -v`
Expected: FAIL — `AttributeError: module ankialign.convert has no attribute 'select_notes'` (after import resolves).

- [ ] **Step 3: Write `select_notes` + `build_plan` in `ankialign/convert.py`**

```python
"""Conversion engine. Uses the anki package; no aqt (GUI) imports so it can be
tested headless."""
from . import config, mapping


def _search(notetype_name: str) -> str:
    excl = " ".join(f"-deck:*{sub}*" for sub in config.EXCLUDE_DECK_SUBSTRINGS)
    return f'note:"{notetype_name}" {excl}'.strip()


def select_notes(col) -> dict:
    """{notetype_name: [note_id, ...]} for in-scope source notes present in col.

    Note: a note with any card in an excluded deck is excluded (search semantics).
    Fine for these single-card vocab notes.
    """
    out = {}
    for name in config.SOURCE_NOTETYPES:
        if col.models.by_name(name) is None:
            continue
        nids = list(col.find_notes(_search(name)))
        if nids:
            out[name] = nids
    return out


def _field_names(col, notetype_name) -> list:
    return [f["name"] for f in col.models.by_name(notetype_name)["flds"]]


def build_plan(col) -> dict:
    """Read-only: runs every mapper dry, collecting counts, samples, and errors."""
    selected = select_notes(col)
    plan = {"sources": {}, "errors": []}
    for name, nids in selected.items():
        mapper = mapping.MAPPERS[name]
        names = _field_names(col, name)
        samples = []
        for i, nid in enumerate(nids):
            src = dict(zip(names, col.get_note(nid).fields))
            try:
                kino = mapper(src)
            except Exception as e:  # surfaced in preview before any mutation
                plan["errors"].append((nid, repr(e)))
                continue
            if i < config.SAMPLE_N:
                samples.append((src, kino))
        plan["sources"][name] = {"count": len(nids), "samples": samples}
    return plan
```

- [ ] **Step 4: Run to verify it passes**

Run: `uv run pytest tests/test_convert.py -v`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add ankialign/convert.py tests/test_convert.py
git commit -m "feat: add note selection and read-only dry-run plan"
```

---

## Task 7: `convert.py` — run_conversion (change + transform, history-preserving)

**Files:**
- Modify: `ankialign/convert.py` (append `run_conversion`)
- Test: `tests/test_convert.py` (add scheduling-preservation test)

- [ ] **Step 1: Write the failing test**

```python
# append to tests/test_convert.py

def test_run_conversion_preserves_schedule_and_maps(col, tmp_path):
    nid = convert.select_notes(col)["MyNote"][0]
    card = col.get_card(col.card_ids_of_note(nid)[0])
    # give the card a non-default schedule
    card.ivl = 42; card.reps = 7; card.due = 12345; card.type = 2
    col.update_card(card)
    cid = card.id

    result = convert.run_conversion(col, backup_dir=str(tmp_path / "bk"))

    note = col.get_note(nid)
    assert note.note_type()["name"] == config.TARGET_NOTETYPE
    assert note["Term"] == "一つ" and note["Reading"] == "ひとつ"
    assert note["Furigana"] == "一[ひと]つ"
    assert note["Meaning"] == "one" and note["Term Audio"] == "[sound:a.mp3]"
    # same card, schedule intact
    c2 = col.get_card(cid)
    assert (c2.ivl, c2.reps, c2.due, c2.type) == (42, 7, 12345, 2)
    assert result["converted"]["MyNote"] == 1


def test_run_conversion_aborts_without_target(tmp_path):
    from anki.collection import Collection
    c = Collection(str(tmp_path / "empty.anki2"))
    try:
        with pytest.raises(RuntimeError, match="target notetype"):
            convert.run_conversion(c, backup_dir=str(tmp_path / "bk"))
    finally:
        c.close()
```

- [ ] **Step 2: Run to verify it fails**

Run: `uv run pytest tests/test_convert.py -k run_conversion -v`
Expected: FAIL — `AttributeError: ... 'run_conversion'`.

- [ ] **Step 3: Append `run_conversion` to `ankialign/convert.py`**

```python
def run_conversion(col, backup_dir: str) -> dict:
    """Backup, then convert all in-scope source notes to the target note type in
    one undoable op, preserving card history. Returns {'converted': {name: n}}."""
    target = col.models.by_name(config.TARGET_NOTETYPE)
    if target is None:
        raise RuntimeError("target notetype missing: " + config.TARGET_NOTETYPE)

    selected = select_notes(col)
    if not any(selected.values()):
        raise RuntimeError("no in-scope notes to convert")

    col.create_backup(backup_folder=backup_dir, force=True, wait_for_completion=True)

    pos = col.add_custom_undo_entry("AnkiAlign: unify note types")
    converted = {}
    target_field_names = {f["name"] for f in target["flds"]}

    for name, nids in selected.items():
        model = col.models.by_name(name)
        names = _field_names(col, name)
        snapshot = {nid: dict(zip(names, col.get_note(nid).fields)) for nid in nids}

        # switch note type + map every template onto the target's first template
        fmap = {i: None for i in range(len(names))}          # discard; we repopulate
        cmap = {t["ord"]: 0 for t in model["tmpls"]}
        col.models.change(model, list(nids), target, fmap, cmap)

        mapper = mapping.MAPPERS[name]
        for nid in nids:
            note = col.get_note(nid)
            for fname, val in mapper(snapshot[nid]).items():
                if fname in target_field_names:
                    note[fname] = val
            col.update_note(note)

        converted[name] = len(nids)

    col.merge_undo_entries(pos)
    return {"converted": converted}
```

- [ ] **Step 4: Run to verify it passes**

Run: `uv run pytest tests/test_convert.py -v`
Expected: PASS (all convert tests, incl. scheduling preserved).

- [ ] **Step 5: Commit**

```bash
git add ankialign/convert.py tests/test_convert.py
git commit -m "feat: history-preserving run_conversion (change + transform)"
```

---

## Task 8: `template.py` — front markup-strip fix + styling

Applies the goal-#4 styling and the "plain sentence when no cloze" front fix to the
Kinomoto note type. The strip happens client-side: a small front script replaces the
`#sentence` content with surface text when there is no cloze.

**Files:**
- Create: `ankialign/template.py`
- Create: `ankialign/kinomoto.css` (the approved `custom_css` from `research/template-preview/build.py`)
- Test: `tests/test_template.py`

- [ ] **Step 1: Copy the approved CSS into the package**

```bash
# extract the custom_css block produced by the preview build and save it:
uv run python - <<'PY'
import re, pathlib
src = pathlib.Path("research/template-preview/build.py").read_text()
# the block is delimited by the comment markers added during goal #4
m = re.search(r'custom_css = kino_css \+ f""".*?"""', src, re.S)
block = m.group(0)
# strip the python wrapper -> keep only the appended css after the f-string head
css = block.split('"""', 1)[1].rsplit('"""', 1)[0]
css = css.replace("{{", "{").replace("}}", "}")   # un-escape f-string braces
pathlib.Path("ankialign/kinomoto.css").write_text(css.strip() + "\n")
print(pathlib.Path("ankialign/kinomoto.css").read_text())
PY
```
Expected: prints the CSS (Lapis serif stack, weight 500, toned palette, `#sentence rt {display:none}`, etc.).

- [ ] **Step 2: Write the failing test**

```python
# tests/test_template.py
import pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankialign import template, config

KINOMOTO_FIELDS = ["Term", "Reading", "Furigana", "Sentence", "SentenceFurigana",
    "Cloze Before", "Cloze Inside", "Cloze After", "Meaning", "Expanded Meaning",
    "Collapsed Meaning", "Notes", "Context", "Frequency", "Pitch Accent", "Image",
    "Term Audio", "Sentence Audio"]


@pytest.fixture
def col(tmp_path):
    c = Collection(str(tmp_path / "t.anki2"))
    m = c.models.new(config.TARGET_NOTETYPE)
    for f in KINOMOTO_FIELDS:
        c.models.add_field(m, c.models.new_field(f))
    t = c.models.new_template("Card 1")
    t["qfmt"] = '<div id="sentence">{{Sentence}}</div>'
    t["afmt"] = '<div id="sentence" data-jrp-generate>{{Sentence}}</div>'
    c.models.add_template(m, t)
    c.models.add(m)
    yield c
    c.close()


def test_apply_injects_strip_marker_and_css(col):
    template.apply(col)
    m = col.models.by_name(config.TARGET_NOTETYPE)
    assert "ankialign-front-strip" in m["tmpls"][0]["qfmt"]   # marker for the front script
    assert "AnkiAlign custom" in m["css"]                      # styling applied

def test_apply_is_idempotent(col):
    template.apply(col); first = col.models.by_name(config.TARGET_NOTETYPE)["css"]
    template.apply(col); second = col.models.by_name(config.TARGET_NOTETYPE)["css"]
    assert first == second   # no duplicate injection
```

- [ ] **Step 3: Run to verify it fails**

Run: `uv run pytest tests/test_template.py -v`
Expected: FAIL — `AttributeError: ... 'apply'`.

- [ ] **Step 4: Write `ankialign/template.py`**

```python
"""Apply the goal-#4 styling and the 'plain sentence when no cloze' front fix
to the Kinomoto note type. Idempotent (guarded by marker comments)."""
import os
from . import config

_CSS_MARKER = "/* ---- AnkiAlign custom"
_FRONT_MARKER = "ankialign-front-strip"

# Client-side strip: when there's no cloze, show the sentence as surface text
# (remove jidoujisho/JRP markup) instead of raw {..}.
_FRONT_SCRIPT = """
<script id="ankialign-front-strip">
(function(){
  if (`{{Cloze Inside}}`.trim()) return;          // cloze present -> leave as-is
  var el = document.getElementById("sentence");
  if (!el) return;
  var t = el.textContent;
  t = t.replace(/\\{([^{}]*)\\}/g, function(_, u){ return u.replace(/;[^;]*$/, ""); });
  t = t.replace(/\\[([^\\[\\]|=]*)=([^\\[\\]]*)\\]/g, "$1");   // [surface=base]
  t = t.replace(/\\[([^\\[\\]|]+)\\|([^\\[\\]]+)\\]/g, "$1");  // [漢字|かな] -> 漢字
  el.textContent = t;
})();
</script>"""


def _css() -> str:
    path = os.path.join(os.path.dirname(__file__), "kinomoto.css")
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def apply(col) -> None:
    m = col.models.by_name(config.TARGET_NOTETYPE)
    if m is None:
        raise RuntimeError("target notetype missing: " + config.TARGET_NOTETYPE)
    css = _css()
    if _CSS_MARKER not in m["css"]:
        m["css"] = m["css"].rstrip() + "\n\n" + css
    tmpl = m["tmpls"][0]
    if _FRONT_MARKER not in tmpl["qfmt"]:
        tmpl["qfmt"] = tmpl["qfmt"].rstrip() + "\n" + _FRONT_SCRIPT
    col.models.update_dict(m)
```

- [ ] **Step 5: Run to verify it passes**

Run: `uv run pytest tests/test_template.py -v`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add ankialign/template.py ankialign/kinomoto.css tests/test_template.py
git commit -m "feat: apply Kinomoto styling + front markup-strip fix (idempotent)"
```

---

## Task 9: `preview.py` + `__init__.py` — GUI shell (manual verification)

GUI code can't be unit-tested meaningfully; the logic it calls (`build_plan`,
`run_conversion`, `template.apply`) is already covered. Keep these thin.

**Files:**
- Create: `ankialign/preview.py`
- Modify: `ankialign/__init__.py`

- [ ] **Step 1: Write `ankialign/preview.py`**

```python
"""Read-only dry-run dialog: shows the plan, then runs the conversion on confirm."""
from aqt import mw
from aqt.qt import (QDialog, QVBoxLayout, QLabel, QTextEdit, QPushButton, QHBoxLayout)
from aqt.utils import showInfo, askUser
from aqt.operations import CollectionOp
from . import convert, template


def _plan_text(plan: dict) -> str:
    lines = []
    for name, info in plan["sources"].items():
        lines.append(f"{name}: {info['count']} notes")
        for src, kino in info["samples"][:3]:
            lines.append(f"   • {kino.get('Term','')}  ←  "
                         f"{src.get('Target Word', src.get('Term',''))}")
    if plan["errors"]:
        lines.append(f"\n⚠ {len(plan['errors'])} mapping errors (will be skipped):")
        for nid, err in plan["errors"][:10]:
            lines.append(f"   nid {nid}: {err}")
    return "\n".join(lines) or "No in-scope notes found."


def show() -> None:
    plan = convert.build_plan(mw.col)
    dlg = QDialog(mw)
    dlg.setWindowTitle("AnkiAlign — Unify note types (preview)")
    lay = QVBoxLayout(dlg)
    lay.addWidget(QLabel("Dry run — nothing has changed yet:"))
    box = QTextEdit(); box.setReadOnly(True); box.setPlainText(_plan_text(plan))
    lay.addWidget(box)
    btns = QHBoxLayout()
    cancel = QPushButton("Cancel"); convert_btn = QPushButton("Backup && Convert")
    btns.addWidget(cancel); btns.addWidget(convert_btn); lay.addLayout(btns)
    cancel.clicked.connect(dlg.reject)

    def do_convert():
        dlg.accept()
        backup_dir = mw.pm.backupFolder()

        def op(col):
            res = convert.run_conversion(col, backup_dir=backup_dir)
            template.apply(col)
            return res

        def done(res):
            c = res["converted"]
            showInfo("Converted: " + ", ".join(f"{k} {v}" for k, v in c.items())
                     + f"\n\nBackup: {backup_dir}\nUndo with Ctrl+Z if needed.")

        CollectionOp(parent=mw, op=op).success(done).run_in_background()

    convert_btn.clicked.connect(do_convert)
    dlg.exec()
```

- [ ] **Step 2: Write `ankialign/__init__.py`**

```python
"""AnkiAlign add-on entry point: Tools menu action."""
from aqt import mw
from aqt.qt import QAction
from . import preview


def _add_menu():
    action = QAction("AnkiAlign: Unify note types…", mw)
    action.triggered.connect(preview.show)
    mw.form.menuTools.addAction(action)


_add_menu()
```

- [ ] **Step 3: Verify the package imports cleanly (no syntax errors)**

Run: `uv run python -c "import ast; [ast.parse(open(f).read()) for f in ['ankialign/preview.py','ankialign/__init__.py']]; print('ok')"`
Expected: `ok` (we don't import aqt here — it's only present inside Anki).

- [ ] **Step 4: Commit**

```bash
git add ankialign/preview.py ankialign/__init__.py
git commit -m "feat: Tools menu action + read-only preview dialog"
```

---

## Task 10: Install into Anki + manual end-to-end verification

**Files:**
- Create: `scripts/install-dev.sh`

- [ ] **Step 1: Write the dev install script**

```bash
# scripts/install-dev.sh
#!/usr/bin/env bash
set -euo pipefail
ADDONS="$HOME/.local/share/Anki2/addons21"
ln -sfn "$(cd "$(dirname "$0")/.." && pwd)/ankialign" "$ADDONS/ankialign"
echo "Linked ankialign into $ADDONS"
```

- [ ] **Step 2: Make it executable and run it**

Run: `chmod +x scripts/install-dev.sh && ./scripts/install-dev.sh`
Expected: `Linked ankialign into …/addons21`.

- [ ] **Step 3: Manual verification (Anki must be closed first; back up is automatic but make your own copy too)**

1. Copy your real collection somewhere safe as an extra precaution.
2. Launch Anki → **Tools → "AnkiAlign: Unify note types…"**.
3. In the preview, confirm counts (~MyNote 5022, Yuuna 211, Kanji Study 259) and that the Term samples look right (一つ, etc.).
4. Click **Backup & Convert**. Confirm the success dialog shows the counts and a backup path.
5. Browse: filter `note:"jidoujisho Kinomoto"` — converted notes should appear with Term/Reading/Furigana/Meaning/audio populated; open a converted card and check front (plain sentence, no raw `{..}`) and back (pitch renders).
6. Open a converted card's **Card Info** — confirm review history/interval is intact.
7. Test **Ctrl+Z** (Undo "AnkiAlign: unify note types") reverts everything.

- [ ] **Step 4: Commit**

```bash
git add scripts/install-dev.sh
git commit -m "chore: add dev install script + manual verification steps"
```

---

## Self-review notes (resolved)

- **Spec coverage:** mechanism (snapshot→change→transform) → Task 7; three mapping tables → Task 5; dropped fields → Task 5 test asserts absence; front-strip fix → Task 8; backup + single undo + read-only preview + abort guards + idempotency → Tasks 6–8; testing (unit + ground-truth + integration + scheduling) → Tasks 3,4,5,6,7. Out-of-scope items (pitch/audio/cloze) intentionally absent.
- **Type/name consistency:** `select_notes`/`build_plan`/`run_conversion` signatures match across Tasks 6–7 and the GUI in Task 9; `MAPPERS` keys match `SOURCE_NOTETYPES`; `markup` function names match between Tasks 3 and 5; `template.apply(col)` matches its call in Task 9.
- **Known nuance documented in code:** search-based selection excludes a note if *any* of its cards is in an excluded deck (fine for single-card vocab notes).
```
