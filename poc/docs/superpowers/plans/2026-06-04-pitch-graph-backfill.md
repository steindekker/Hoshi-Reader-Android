# Term Pitch-Graph Backfill — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fill the empty `Pitch Accent` field on `jidoujisho Kinomoto` notes with a self-contained jidoujisho-style SVG pitch graph, generated from `(Term, Reading)` looked up in アクセント辞典v2 (primary accent only).

**Architecture:** Three pure, anki-free units — an SVG generator (`pitch_svg.py`), a dict-index lookup (`pitch_index.py`) over a prebuilt data file, and an offline index builder (`build_index.py`) — composed by an idempotent engine (`pitch.py`, mirrors `convert.normalize_media`) and surfaced via a Tools-menu dry-run dialog. Validated against the 2,256 cards that already carry jidoujisho's own graphs.

**Tech Stack:** Python 3.11+, stdlib only (`marshal`, `gzip`, `re`), `anki` package (dev/test + runtime), `aqt` (GUI), `pytest`.

---

## Context the implementer needs

- **Pure-vs-impure boundary is sacred here.** `markup.py`, and the new `pitch_svg.py`/`pitch_index.py`, import **no** `anki`/`aqt` — they are unit-tested standalone. `pitch.py` imports `anki`-level `col` only (no `aqt`). GUI lives in `preview.py`/`__init__.py`. Follow this split exactly.
- **Gitignored data (NOT in this worktree):** `collection.anki2` and the extracted dicts. Reference them by **absolute path in the main checkout**:
  - Source dict: `~/projects/anki-align/research/pitch-pipeline/dicts/d/アクセント辞典v2/term_meta_bank_*.json`
  - Real collection: `~/projects/anki-align/collection.anki2`
  - To run the ground-truth audit locally, symlink the collection into the worktree root (it's gitignored, so it can't be committed):
    `ln -s ~/projects/anki-align/collection.anki2 ./collection.anki2`
- **Reference material** (staged under `research/pitch-pipeline/reference/`): `kino_template.js` (the JRP renderer — authoritative source for `split_moras`, `to_kata`, the `i_dan`/`e_comp` kana sets) and `validate_pitch_dicts.py` (the dict-loading + lookup logic already proven in the dict study).
- **Spec:** `docs/superpowers/specs/2026-06-04-pitch-graph-backfill-design.md`. **Dict report:** `research/pitch-pipeline/REPORT-pitch-dicts.md`.
- **Pitch position semantics:** `0` = heiban, `1` = atamadaka, `k≥2` = downstep after mora `k`. The "particle" slot is one virtual point after the last mora.
- Run tests with `uv run pytest` (the project uses `uv`; `anki` is a dev dep). Single test: `uv run pytest tests/test_pitch_svg.py::test_name -v`.

## File structure

| File | Responsibility |
|---|---|
| `ankialign/pitch_svg.py` (create) | Pure SVG generator: `split_moras(reading)`, `render_svg(reading, accent)`. |
| `ankialign/pitch_index.py` (create) | Pure lookup over the prebuilt index: `PitchIndex.load(path)`, `.lookup(term, reading) -> int | None`, plus `to_hira`. |
| `ankialign/data/pitch_index.marshal.gz` (create, generated) | `{ "term\treading_hira": position }`, ~327k keys. Built once, committed. |
| `ankialign/pitch.py` (create) | Engine: `build_pitch_plan(col)`, `backfill_pitch(col)`. Idempotent. |
| `research/pitch-pipeline/build_index.py` (create) | Offline builder: アクセント辞典v2 → `pitch_index.marshal.gz`. |
| `ankialign/preview.py` (modify) | Add `show_pitch()` dry-run dialog. |
| `ankialign/__init__.py` (modify) | Add the "Backfill pitch graphs…" menu action. |
| `tests/test_pitch_svg.py` (create) | Unit tests for mora-splitting + SVG strings. |
| `tests/test_pitch_index.py` (create) | Unit tests for normalization + fallback. |
| `tests/test_pitch_engine.py` (create) | Engine tests over an in-memory `anki.Collection`. |
| `tests/test_pitch_groundtruth.py` (create) | Audit: dict accent vs the 2,256 existing SVGs. |

---

## Task 1: `pitch_svg.py` — mora splitting

**Files:**
- Create: `ankialign/pitch_svg.py`
- Test: `tests/test_pitch_svg.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_pitch_svg.py
from ankialign import pitch_svg


def test_split_moras_basic():
    assert pitch_svg.split_moras("いへん") == ["い", "へ", "ん"]
    assert pitch_svg.split_moras("かみ") == ["か", "み"]


def test_split_moras_combines_small_kana():
    # しゃ is one mora; small kana attaches to the い-dan base
    assert pitch_svg.split_moras("しゃしん") == ["しゃ", "し", "ん"]
    assert pitch_svg.split_moras("きょう") == ["きょ", "う"]


def test_split_moras_long_vowel_and_sokuon_are_their_own_mora():
    assert pitch_svg.split_moras("コーヒー") == ["コ", "ー", "ヒ", "ー"]
    assert pitch_svg.split_moras("がっこう") == ["が", "っ", "こ", "う"]


def test_split_moras_preserves_original_script():
    # katakana stays katakana, hiragana stays hiragana
    assert pitch_svg.split_moras("アーカイブ") == ["ア", "ー", "カ", "イ", "ブ"]
```

- [ ] **Step 2: Run, verify it fails**

Run: `uv run pytest tests/test_pitch_svg.py -v`
Expected: FAIL — `ModuleNotFoundError: ankialign.pitch_svg`.

- [ ] **Step 3: Implement `split_moras`**

```python
# ankialign/pitch_svg.py
"""Pure SVG pitch-graph generator (jidoujisho-style). No anki/aqt imports.

Mora-splitting + accent-contour rules are ported from the Kinomoto card
template's JRP renderer (research/pitch-pipeline/reference/kino_template.js):
split_moras / i_dan / e_comp.
"""

# い-dan kana that combine with small ゃゅょ
_I_DAN = set("キギシジチヂニヒビピミリ")
# bases that combine with small ェ
_E_COMP = set("イウキギクグシジチツニヒビピフミリヴ")


def _to_kata(s: str) -> str:
    """Hiragana -> katakana (1:1, length-preserving). Non-kana pass through."""
    return "".join(chr(ord(c) + 0x60) if "ぁ" <= c <= "ゖ" else c for c in s)


def _combines(ck: str, nk: str) -> bool:
    """True if katakana base `ck` and following small kana `nk` form one mora."""
    return (
        (ck in _I_DAN and nk in "ャュョ")
        or (nk == "ヮ" and ck in "クグ")
        or (nk == "ァ" and ck in "ツフヴ")
        or (nk == "ィ" and ck in "クグスズテツデフイウヴ")
        or (nk == "ゥ" and ck in "トドホウ")
        or (nk == "ェ" and ck in _E_COMP)
        or (nk == "ォ" and ck in "クグツフウヴ")
        or (nk == "ャ" and ck in "フヴ")
        or (nk == "ュ" and ck in "テデフウヴ")
        or (nk == "ョ" and ck in "フヴ")
    )


def split_moras(reading: str) -> list[str]:
    """Segment a kana reading into moras, preserving the original script.

    Matching uses a katakana-normalized copy (same length), but the emitted
    substrings are sliced from the original `reading`.
    """
    kata = _to_kata(reading)
    moras: list[str] = []
    i, n = 0, len(reading)
    while i < n:
        ck = kata[i]
        nk = kata[i + 1] if i + 1 < n else None
        if nk is not None and _combines(ck, nk):
            moras.append(reading[i:i + 2])
            i += 2
        else:
            moras.append(reading[i])
            i += 1
    return moras
```

- [ ] **Step 4: Run, verify pass**

Run: `uv run pytest tests/test_pitch_svg.py -v`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add ankialign/pitch_svg.py tests/test_pitch_svg.py
git commit -m "feat(pitch): mora splitting ported from JRP renderer"
```

---

## Task 2: `pitch_svg.py` — accent contour + SVG render

**Files:**
- Modify: `ankialign/pitch_svg.py`
- Test: `tests/test_pitch_svg.py`

- [ ] **Step 1: Write the failing tests** (append)

```python
def test_is_high_patterns():
    # n moras; index n is the particle slot
    # heiban (0): mora0 low, everything else high
    assert [pitch_svg._is_high(i, 0, 3) for i in range(4)] == [False, True, True, True]
    # atamadaka (1): mora0 high, rest low
    assert [pitch_svg._is_high(i, 1, 3) for i in range(4)] == [True, False, False, False]
    # nakadaka (2) on 3 moras: low, high, low, low(particle)
    assert [pitch_svg._is_high(i, 2, 3) for i in range(4)] == [False, True, False, False]
    # odaka (2) on 2 moras: low, high, low(particle)
    assert [pitch_svg._is_high(i, 2, 2) for i in range(3)] == [False, True, False]


def test_render_svg_atamadaka_kami():
    svg = pitch_svg.render_svg("かみ", 1)
    assert svg == (
        '<svg xmlns="http://www.w3.org/2000/svg" width="61.2px" height="45px" '
        'viewBox="0 0 102 75">'
        '<text x="5" y="67.5" style="font-size:20px;font-family:sans-serif;fill:currentColor;">か</text>'
        '<text x="40" y="67.5" style="font-size:20px;font-family:sans-serif;fill:currentColor;">み</text>'
        '<path d="m 16,5 35,25" style="fill:none;stroke:currentColor;stroke-width:1.5;" />'
        '<path d="m 51,30 35,0" style="fill:none;stroke:currentColor;stroke-width:1.5;" />'
        '<circle r="5" cx="16" cy="5" style="opacity:1;fill:currentColor;"></circle>'
        '<circle r="5" cx="51" cy="30" style="opacity:1;fill:currentColor;"></circle>'
        '<circle r="4" cx="86" cy="30" stroke="currentColor" style="opacity:1;fill:none;"></circle>'
        '</svg>'
    )


def test_render_svg_heiban_dimensions():
    svg = pitch_svg.render_svg("いへん", 0)
    assert 'viewBox="0 0 137 75"' in svg
    assert 'width="82.2px"' in svg
    # heiban: first mora low (cy=30), second mora high (cy=5)
    assert '<circle r="5" cx="16" cy="30"' in svg
    assert '<circle r="5" cx="51" cy="5"' in svg
    # particle slot is the hollow r=4 circle, high for heiban
    assert '<circle r="4" cx="121" cy="5"' in svg


def test_render_svg_empty_reading():
    assert pitch_svg.render_svg("", 0) == ""
```

- [ ] **Step 2: Run, verify it fails**

Run: `uv run pytest tests/test_pitch_svg.py -v`
Expected: FAIL — `_is_high` / `render_svg` not defined.

- [ ] **Step 3: Implement contour + renderer** (append to `pitch_svg.py`)

```python
def _is_high(i: int, accent: int, n: int) -> bool:
    """Pitch height of point `i` (0..n; n = particle slot) for `n`-mora word."""
    if accent == 0:          # heiban
        return i != 0
    if accent == 1:          # atamadaka
        return i == 0
    return 1 <= i < accent   # nakadaka/odaka: high on moras 1..accent-1


def _f(x: float) -> str:
    """Compact float -> str (61.2 not 61.199999…)."""
    return f"{x:g}"


def render_svg(reading: str, accent: int) -> str:
    """Self-contained jidoujisho-style SVG pitch graph for `reading` + `accent`."""
    moras = split_moras(reading)
    n = len(moras)
    if n == 0:
        return ""
    pts = [(16 + 35 * i, 5 if _is_high(i, accent, n) else 30) for i in range(n + 1)]
    vb_w = 35 * n + 32
    out = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{_f(vb_w * 0.6)}px" '
        f'height="45px" viewBox="0 0 {vb_w} 75">'
    ]
    for i, m in enumerate(moras):
        out.append(
            f'<text x="{5 + 35 * i}" y="67.5" '
            f'style="font-size:20px;font-family:sans-serif;fill:currentColor;">{m}</text>'
        )
    for i in range(n):
        (x0, y0), (_, y1) = pts[i], pts[i + 1]
        out.append(
            f'<path d="m {x0},{y0} 35,{y1 - y0}" '
            f'style="fill:none;stroke:currentColor;stroke-width:1.5;" />'
        )
    for i in range(n):
        x, y = pts[i]
        out.append(f'<circle r="5" cx="{x}" cy="{y}" style="opacity:1;fill:currentColor;"></circle>')
    px, py = pts[n]
    out.append(
        f'<circle r="4" cx="{px}" cy="{py}" stroke="currentColor" '
        f'style="opacity:1;fill:none;"></circle>'
    )
    out.append("</svg>")
    return "".join(out)
```

- [ ] **Step 4: Run, verify pass**

Run: `uv run pytest tests/test_pitch_svg.py -v`
Expected: PASS (all).

- [ ] **Step 5: Commit**

```bash
git add ankialign/pitch_svg.py tests/test_pitch_svg.py
git commit -m "feat(pitch): accent contour + SVG renderer"
```

---

## Task 3: `pitch_index.py` — lookup + normalization

**Files:**
- Create: `ankialign/pitch_index.py`
- Test: `tests/test_pitch_index.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_pitch_index.py
import gzip, marshal
from ankialign.pitch_index import PitchIndex, to_hira


def test_to_hira():
    assert to_hira("アーカイブ") == "あーかいぶ"
    assert to_hira("いへん") == "いへん"


def _make(tmp_path, mapping):
    p = tmp_path / "idx.marshal.gz"
    with gzip.open(p, "wb") as f:
        f.write(marshal.dumps(mapping))
    return PitchIndex.load(str(p))


def test_lookup_exact_term_reading(tmp_path):
    idx = _make(tmp_path, {"異変\tいへん": 0})
    assert idx.lookup("異変", "いへん") == 0


def test_lookup_normalizes_katakana_reading(tmp_path):
    # index stored with hiragana-normalized reading; query gives katakana
    idx = _make(tmp_path, {"アーカイブ\tあーかいぶ": 3})
    assert idx.lookup("アーカイブ", "アーカイブ") == 3


def test_lookup_kana_headword_fallback(tmp_path):
    # term differs (kanji on card) but dict headword is kana == reading
    idx = _make(tmp_path, {"ある\tある": 1})
    assert idx.lookup("有る", "ある") == 1


def test_lookup_miss_returns_none(tmp_path):
    idx = _make(tmp_path, {"異変\tいへん": 0})
    assert idx.lookup("存在しない", "そんざい") is None
```

- [ ] **Step 2: Run, verify it fails**

Run: `uv run pytest tests/test_pitch_index.py -v`
Expected: FAIL — module missing.

- [ ] **Step 3: Implement**

```python
# ankialign/pitch_index.py
"""Pure pitch-accent lookup over a prebuilt index. No anki/aqt imports.

Index format: a dict { "term\treading_hira": position } serialized with
stdlib marshal + gzip (see research/pitch-pipeline/build_index.py).
"""
import gzip
import marshal


def to_hira(s: str) -> str:
    """Katakana -> hiragana (1:1). Non-kana (incl. ー) pass through."""
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in s)


class PitchIndex:
    def __init__(self, mapping: dict):
        self._m = mapping

    @classmethod
    def load(cls, path: str) -> "PitchIndex":
        with gzip.open(path, "rb") as f:
            return cls(marshal.loads(f.read()))

    def lookup(self, term: str, reading: str):
        """Primary downstep position for (term, reading), or None.

        Tries: exact (term, reading_hira); then kana-headword fallbacks where
        the dict headword is the reading itself.
        """
        r = to_hira(reading.strip())
        term = term.strip()
        for key in (f"{term}\t{r}", f"{reading.strip()}\t{r}", f"{r}\t{r}"):
            if key in self._m:
                return self._m[key]
        return None
```

- [ ] **Step 4: Run, verify pass**

Run: `uv run pytest tests/test_pitch_index.py -v`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add ankialign/pitch_index.py tests/test_pitch_index.py
git commit -m "feat(pitch): prebuilt-index lookup with kana normalization"
```

---

## Task 4: `build_index.py` + generate the data file

**Files:**
- Create: `research/pitch-pipeline/build_index.py`
- Create (generated): `ankialign/data/pitch_index.marshal.gz`

- [ ] **Step 1: Write the builder**

```python
# research/pitch-pipeline/build_index.py
"""Build the compact pitch index from アクセント辞典v2 (primary accent only).

Reads the Yomitan term_meta banks (absolute path in the main checkout, since
the extracted dict is gitignored) and writes ankialign/data/pitch_index.marshal.gz
keyed by "term\treading_hira" -> primary downstep position.

Run once:  uv run python research/pitch-pipeline/build_index.py
"""
import glob
import gzip
import json
import marshal
import os
import sys

# pure helper, reused from the shipped module
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "ankialign"))
from pitch_index import to_hira  # noqa: E402

SRC = "~/projects/anki-align/research/pitch-pipeline/dicts/d/アクセント辞典v2"
OUT = os.path.join(os.path.dirname(__file__), "..", "..",
                   "ankialign", "data", "pitch_index.marshal.gz")


def main() -> None:
    banks = sorted(glob.glob(os.path.join(SRC, "term_meta_bank_*.json")))
    if not banks:
        raise SystemExit(f"no term_meta banks under {SRC}")
    idx: dict[str, int] = {}
    for bank in banks:
        with open(bank, encoding="utf-8") as fh:
            data = json.load(fh)
        for e in data:
            if len(e) < 3 or e[1] != "pitch":
                continue
            term, info = e[0], e[2]
            reading = info.get("reading", term)
            pitches = info.get("pitches", [])
            if not pitches:
                continue
            pos = pitches[0].get("position")   # pitches[0] = primary
            if not isinstance(pos, int):
                continue
            idx.setdefault(f"{term}\t{to_hira(reading)}", pos)  # first wins
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with gzip.open(OUT, "wb", compresslevel=9) as f:
        f.write(marshal.dumps(idx))
    size = os.path.getsize(OUT)
    print(f"wrote {len(idx)} keys -> {OUT} ({size/1e6:.1f} MB)")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Run the builder**

Run: `uv run python research/pitch-pipeline/build_index.py`
Expected: `wrote ~320000 keys -> .../pitch_index.marshal.gz (~3-6 MB)`. Confirm the file exists: `ls -la ankialign/data/pitch_index.marshal.gz`.

- [ ] **Step 3: Smoke-test the generated index**

```bash
uv run python -c "from ankialign.pitch_index import PitchIndex; \
i=PitchIndex.load('ankialign/data/pitch_index.marshal.gz'); \
print('異変',i.lookup('異変','いへん')); print('加味',i.lookup('加味','かみ'))"
```
Expected: two integer positions printed (not None).

- [ ] **Step 4: Commit** (the builder AND the generated data — the add-on ships the data)

```bash
git add research/pitch-pipeline/build_index.py ankialign/data/pitch_index.marshal.gz
git commit -m "feat(pitch): build + ship アクセント辞典v2 compact index"
```

---

## Task 5: Ground-truth audit — dict accent vs the 2,256 existing SVGs

This is the spec's key validation: parse the accent out of each existing jidoujisho SVG and confirm the dict lookup agrees. Validates the **dict** (the generator is pinned by Task 2 unit tests).

**Files:**
- Create: `tests/test_pitch_groundtruth.py`

- [ ] **Step 1: Symlink the real collection (gitignored, won't commit)**

```bash
ln -sf ~/projects/anki-align/collection.anki2 ./collection.anki2
```

- [ ] **Step 2: Write the audit test**

```python
# tests/test_pitch_groundtruth.py
"""Audit: the accent implied by each existing jidoujisho Pitch Accent SVG
should match アクセント辞典v2's lookup for (Term, Reading) at a high rate.

Parses the downstep position from the SVG's circle heights (cy=5 high / 30 low)
and compares to the shipped index. Skips if the collection isn't present
(gitignored; symlink it in to run locally)."""
import os
import re
import sqlite3
import pytest
from ankialign.pitch_index import PitchIndex

DB = os.path.join(os.path.dirname(__file__), "..", "collection.anki2")
IDX = os.path.join(os.path.dirname(__file__), "..", "ankialign", "data",
                   "pitch_index.marshal.gz")
FS = "\x1f"
pytestmark = pytest.mark.skipif(
    not (os.path.exists(DB) and os.path.exists(IDX)),
    reason="needs collection.anki2 (symlink it) + built index",
)

_CIRC = re.compile(r'<circle r="(\d)" cx="\d+" cy="(\d+)"')


def _accent_from_svg(svg: str):
    """Reconstruct downstep position from circle heights, or None if unparseable."""
    circ = _CIRC.findall(svg)
    if len(circ) < 2:
        return None
    highs = [cy == "5" for _r, cy in circ]   # last entry = particle slot
    if highs[0]:
        return 1                              # atamadaka
    if highs[-1]:
        return 0                              # heiban (particle stays high)
    hi = [i for i, h in enumerate(highs) if h]
    if not hi:
        return None
    return max(hi) + 1                        # nakadaka/odaka: drop after last high


def test_dict_agrees_with_existing_svgs():
    con = sqlite3.connect(f"file:{DB}?immutable=1", uri=True)
    mid = con.execute(
        "SELECT id FROM notetypes WHERE name='jidoujisho Kinomoto'").fetchone()[0]
    names = [r[0] for r in con.execute(
        "SELECT name FROM fields WHERE ntid=? ORDER BY ord", (mid,))]
    ix = {n: i for i, n in enumerate(names)}
    idx = PitchIndex.load(IDX)

    total = found = agree = 0
    mism = []
    for (flds,) in con.execute("SELECT flds FROM notes WHERE mid=?", (mid,)):
        f = flds.split(FS)
        term, reading, pa = f[ix["Term"]], f[ix["Reading"]], f[ix["Pitch Accent"]]
        if "<svg" not in pa or not reading.strip():
            continue
        gold = _accent_from_svg(pa)
        if gold is None:
            continue
        total += 1
        pos = idx.lookup(term, reading)
        if pos is None:
            continue
        found += 1
        if pos == gold:
            agree += 1
        elif len(mism) < 25:
            mism.append((term, reading, gold, pos))

    print(f"\ngold-parsed: {total}  dict-found: {found} ({100*found/total:.1f}%)  "
          f"agree: {agree} ({100*agree/found:.1f}% of found)")
    print("sample mismatches (term, reading, svg_accent, dict_accent):")
    for m in mism:
        print("  ", m)
    # Coverage and agreement thresholds (dict study predicts ~97% / ~99%+).
    assert found / total > 0.90
    assert agree / found > 0.95
```

- [ ] **Step 3: Run the audit**

Run: `uv run pytest tests/test_pitch_groundtruth.py -v -s`
Expected: PASS; printed coverage ≳95% and agreement ≳98%. **If agreement is low**, inspect the printed mismatches: most likely an `_accent_from_svg` parsing bug (re-derive from a few raw SVGs) before suspecting the dict. Fix the parser, not the thresholds, unless mismatches are genuinely dict disagreements.

- [ ] **Step 4: Commit**

```bash
git add tests/test_pitch_groundtruth.py
git commit -m "test(pitch): ground-truth audit of dict vs existing SVGs"
```

---

## Task 6: `pitch.py` engine — plan + backfill

**Files:**
- Create: `ankialign/pitch.py`
- Test: `tests/test_pitch_engine.py`

- [ ] **Step 1: Write the failing test** (mirrors `tests/test_convert.py`'s in-memory collection fixture)

```python
# tests/test_pitch_engine.py
import gzip, marshal
import pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankialign import pitch, config

KINOMOTO_FIELDS = ["Term", "Reading", "Furigana", "Sentence", "SentenceFurigana",
    "Cloze Before", "Cloze Inside", "Cloze After", "Meaning", "Expanded Meaning",
    "Collapsed Meaning", "Notes", "Context", "Frequency", "Pitch Accent", "Image",
    "Term Audio", "Sentence Audio"]


def _kinomoto(col):
    m = col.models.new(config.TARGET_NOTETYPE)
    for fn in KINOMOTO_FIELDS:
        col.models.add_field(m, col.models.new_field(fn))
    t = col.models.new_template("Card 1")
    t["qfmt"] = "{{Term}}"; t["afmt"] = "{{FrontSide}}<hr>{{Reading}}"
    col.models.add_template(m, t)
    col.models.add(m)
    return col.models.by_name(config.TARGET_NOTETYPE)


@pytest.fixture
def col(tmp_path, monkeypatch):
    c = Collection(str(tmp_path / "t.anki2"))
    m = _kinomoto(c)
    c.models.set_current(m)
    deck = c.decks.id("言葉文章")

    def add(term, reading, pa=""):
        n = c.new_note(m)
        n["Term"] = term; n["Reading"] = reading; n["Pitch Accent"] = pa
        c.add_note(n, deck); return n.id

    add("異変", "いへん")               # fillable
    add("加味", "かみ")                 # fillable
    add("既存", "きそん", pa="<svg>existing</svg>")  # already has a graph -> skip
    add("謎語", "なぞご")               # not in index -> not_found
    add("無読", "")                     # no reading -> skipped

    # tiny index file + point the engine at it
    idxfile = tmp_path / "pitch_index.marshal.gz"
    with gzip.open(idxfile, "wb") as f:
        f.write(marshal.dumps({"異変\tいへん": 0, "加味\tかみ": 1}))
    monkeypatch.setattr(pitch, "_INDEX_PATH", str(idxfile))
    yield c
    c.close()


def test_build_pitch_plan_counts(col):
    plan = pitch.build_pitch_plan(col)
    assert plan["counts"] == {"filled": 2, "not_found": 1, "skipped": 1}
    assert plan["counts"]["filled"] == len(plan["samples"]) or len(plan["samples"]) <= config.SAMPLE_N


def test_backfill_fills_only_empty(col):
    res = pitch.backfill_pitch(col)
    assert res == {"filled": 2, "not_found": 1, "skipped": 1}
    nids = list(col.find_notes(f'note:"{config.TARGET_NOTETYPE}"'))
    pas = {col.get_note(i)["Term"]: col.get_note(i)["Pitch Accent"] for i in nids}
    assert "<svg" in pas["異変"] and "<svg" in pas["加味"]
    assert pas["既存"] == "<svg>existing</svg>"   # untouched
    assert pas["謎語"] == "" and pas["無読"] == ""


def test_backfill_is_idempotent(col):
    pitch.backfill_pitch(col)
    res2 = pitch.backfill_pitch(col)
    assert res2["filled"] == 0
```

- [ ] **Step 2: Run, verify it fails**

Run: `uv run pytest tests/test_pitch_engine.py -v`
Expected: FAIL — `ankialign.pitch` missing.

- [ ] **Step 3: Implement the engine**

```python
# ankialign/pitch.py
"""Pitch-graph backfill engine. Uses the anki `col` API; no aqt (GUI) imports
so it can be tested headless. Mirrors convert.normalize_media: idempotent,
writes only changed notes."""
import os
from . import config, pitch_svg
from .pitch_index import PitchIndex

_INDEX_PATH = os.path.join(os.path.dirname(__file__), "data", "pitch_index.marshal.gz")
_FIELD = "Pitch Accent"


def _iter_targets(col, idx):
    """Yield (note, action, svg) for every target note.

    action ∈ {"filled","not_found","skipped"}; svg is set only for "filled".
    Notes that already have a graph are not yielded (idempotent no-op)."""
    for nid in col.find_notes(f'note:"{config.TARGET_NOTETYPE}"'):
        note = col.get_note(nid)
        if note[_FIELD].strip():
            continue
        reading = note["Reading"].strip()
        if not reading:
            yield note, "skipped", None
            continue
        pos = idx.lookup(note["Term"].strip(), reading)
        if pos is None:
            yield note, "not_found", None
            continue
        yield note, "filled", pitch_svg.render_svg(reading, pos)


def build_pitch_plan(col) -> dict:
    """Read-only: counts + a few (term, reading, svg) samples. No mutation."""
    idx = PitchIndex.load(_INDEX_PATH)
    counts = {"filled": 0, "not_found": 0, "skipped": 0}
    samples = []
    for note, action, svg in _iter_targets(col, idx):
        counts[action] += 1
        if action == "filled" and len(samples) < config.SAMPLE_N:
            samples.append((note["Term"], note["Reading"], svg))
    return {"counts": counts, "samples": samples}


def backfill_pitch(col) -> dict:
    """Write SVG graphs into empty Pitch Accent fields. Returns action counts."""
    idx = PitchIndex.load(_INDEX_PATH)
    res = {"filled": 0, "not_found": 0, "skipped": 0}
    for note, action, svg in _iter_targets(col, idx):
        res[action] += 1
        if action == "filled":
            note[_FIELD] = svg
            col.update_note(note)
    return res
```

- [ ] **Step 4: Run, verify pass**

Run: `uv run pytest tests/test_pitch_engine.py -v`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add ankialign/pitch.py tests/test_pitch_engine.py
git commit -m "feat(pitch): idempotent backfill engine (plan + run)"
```

---

## Task 7: GUI — Tools menu action + dry-run dialog

**Files:**
- Modify: `ankialign/preview.py`
- Modify: `ankialign/__init__.py`

- [ ] **Step 1: Add `show_pitch()` to `preview.py`** (append; reuse existing imports `mw`, `QDialog`, `showInfo`, etc., and add `pitch` to the `from . import` line)

Change the import line at the top of `preview.py`:
```python
from . import convert, template, pitch
```

Append:
```python
def _pitch_plan_text(plan: dict) -> str:
    c = plan["counts"]
    lines = [
        f"Will fill:   {c['filled']} notes",
        f"Not in dict: {c['not_found']} (left empty)",
        f"No reading:  {c['skipped']} (skipped)",
        "",
        "Samples (term — reading — accent graph):",
    ]
    for term, reading, _svg in plan["samples"]:
        lines.append(f"   • {term}  —  {reading}")
    return "\n".join(lines)


def show_pitch() -> None:
    plan = pitch.build_pitch_plan(mw.col)
    dlg = QDialog(mw)
    dlg.setWindowTitle("AnkiAlign — Backfill pitch graphs (preview)")
    lay = QVBoxLayout(dlg)
    lay.addWidget(QLabel("Dry run — nothing has changed yet:"))
    box = QTextEdit(); box.setReadOnly(True); box.setPlainText(_pitch_plan_text(plan))
    lay.addWidget(box)
    btns = QHBoxLayout()
    cancel = QPushButton("Cancel"); run = QPushButton("Backup && Backfill")
    btns.addWidget(cancel); btns.addWidget(run); lay.addLayout(btns)
    cancel.clicked.connect(dlg.reject)

    def do_run():
        dlg.accept()
        backup_dir = mw.pm.backupFolder()
        convert.backup_collection(mw.col, backup_dir)   # forced, verified backup
        mw.progress.start(label="AnkiAlign: backfilling pitch graphs…", immediate=True)
        try:
            res = pitch.backfill_pitch(mw.col)
        finally:
            mw.progress.finish()
        mw.reset()
        showInfo(
            f"Filled: {res['filled']}\n"
            f"Not in dict: {res['not_found']}\n"
            f"Skipped (no reading): {res['skipped']}\n\n"
            f"Rollback: restore the backup at\n{backup_dir}")

    run.clicked.connect(do_run)
    dlg.exec()
```

- [ ] **Step 2: Wire the menu action in `__init__.py`**

Replace the `_add_menu` body so both actions are registered:
```python
    def _add_menu():
        unify = QAction("AnkiAlign: Unify note types…", mw)
        unify.triggered.connect(preview.show)
        mw.form.menuTools.addAction(unify)
        backfill = QAction("AnkiAlign: Backfill pitch graphs…", mw)
        backfill.triggered.connect(preview.show_pitch)
        mw.form.menuTools.addAction(backfill)
```

- [ ] **Step 3: Verify it imports headlessly** (no aqt at test time — just confirm modules import)

Run: `uv run python -c "import ankialign.pitch, ankialign.pitch_svg, ankialign.pitch_index; print('ok')"`
Expected: `ok`.

- [ ] **Step 4: Full test suite green**

Run: `uv run pytest -q`
Expected: all pass (including the ground-truth audit if the collection is symlinked; skipped otherwise).

- [ ] **Step 5: Commit**

```bash
git add ankialign/preview.py ankialign/__init__.py
git commit -m "feat(pitch): Tools menu action + dry-run backfill dialog"
```

---

## Final verification

- [ ] `uv run pytest -q` — entire suite green.
- [ ] Ground-truth audit (with collection symlinked) printed coverage ≳95%, agreement ≳98%; sample mismatches reviewed and are genuine dict disagreements, not parser/generator bugs.
- [ ] `git log --oneline` shows one commit per task; working tree clean (the `collection.anki2` symlink is gitignored and untracked — leave it).
- [ ] Report back: final counts from a dry-run `build_pitch_plan` against the real collection (how many of the ~425 empty Kinomoto Pitch Accent fields would be filled / not_found / skipped). To get this without the GUI:
  ```bash
  uv run python -c "import sqlite3" # sanity
  uv run python - <<'PY'
  from anki.collection import Collection
  from ankialign import pitch
  import shutil, tempfile, os
  src='~/projects/anki-align/collection.anki2'
  tmp=tempfile.mkdtemp(); dst=os.path.join(tmp,'c.anki2'); shutil.copy(src,dst)
  c=Collection(dst); print(pitch.build_pitch_plan(c)["counts"]); c.close()
  PY
  ```

## Notes / deferred
- Sentence inline pitch is **out of scope** (separate third-party JRP add-on).
- The unify-run tail-call (auto-backfill after conversion) is intentionally **not** added — the standalone action covers post-conversion notes. Add later only if desired.
