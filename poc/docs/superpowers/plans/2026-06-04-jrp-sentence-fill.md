# JRP Sentence-Fill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Backfill jidoujisho/JRP `{unit;accent}` markup into the ~1,751 plain (and ~1,500 partially-marked) `jidoujisho Kinomoto` Sentence fields so the template's JRP renderer draws furigana + pitch.

**Architecture:** A standalone offline `uv` CLI in the `ankialign/jrp_fill/` package, run with Anki closed. Pure stdlib transforms (`unit`, `align`, `emit`, `splice`) are unit-tested in isolation; `accent`/`tokenize`/`marine_tail` own the heavy deps; `engine`/`cli` open the collection via the `anki` library and write through `col.update_notes` (never raw sqlite). Segmentation is SudachiPy **Mode C** with a **lemma-form reading** accent key and a **Mode C→A split-and-retry** cascade; accent comes from a prebuilt index (priority gold → アクセント辞典v2 → NHK2016) with an optional `marine` neural fallback.

**Tech Stack:** Python ≥3.11, SudachiPy + sudachidict-full (Mode C), the `anki` library, marshal+gzip for the shipped accent index, optional `marine`/`marine-plus`. Spec: `docs/superpowers/specs/2026-06-04-jrp-sentence-generation-design.md`.

**Note on a justified deviation from the spec:** the spec lists a `deinflect.py` suffix-stripper fallback. The spike (`research/jrp-pipeline/work/spike_segment2.py`) reached 98.7% strict coverage using only Sudachi's `dictionary_form()` + lemma-reading, *without* any deinflection. Per YAGNI we **omit `deinflect.py`** in v1; revisit only if the ground-truth gate (Task 11) drops. Likewise the `!` reading-ambiguity mark is deferred (we emit multiple positions and `;Y`, which the gold relies on; `!` is cosmetic).

---

### Task 0: Scaffold the package and dependency group

**Files:**
- Modify: `pyproject.toml`
- Create: `ankialign/jrp_fill/__init__.py`
- Create: `research/jrp-pipeline/` (already exists from the spike)

- [ ] **Step 1: Add the `jrp` dependency group to `pyproject.toml`**

Edit `pyproject.toml` so `[dependency-groups]` reads:

```toml
[dependency-groups]
dev = ["pytest>=8", "anki>=24.11"]
jrp = ["sudachipy>=0.6.8", "sudachidict-full>=20240409"]
```

- [ ] **Step 2: Create the package init**

Create `ankialign/jrp_fill/__init__.py`:

```python
"""Offline JRP sentence-markup generator (run with Anki closed).

Pure transforms: unit, align, emit, splice (stdlib only).
Heavy deps:      accent (shipped index), tokenize (Sudachi), marine_tail.
Collection I/O:  engine, cli (the `anki` library).
"""
```

- [ ] **Step 3: Verify the base test env still resolves**

Run: `uv run --group dev pytest -q`
Expected: existing suite passes (no new tests yet).

- [ ] **Step 4: Commit**

```bash
git add pyproject.toml ankialign/jrp_fill/__init__.py
git commit -m "feat(jrp): scaffold jrp_fill package + uv dependency group"
```

---

### Task 1: `Unit` — the tokenize↔emit interface

**Files:**
- Create: `ankialign/jrp_fill/unit.py`
- Test: `tests/test_jrp_unit.py`

- [ ] **Step 1: Write the failing test**

Create `tests/test_jrp_unit.py`:

```python
from ankialign.jrp_fill.unit import Unit

def test_unit_is_frozen_with_defaults():
    u = Unit(surface="立て", lemma="立てる", reading="たて",
             lemma_reading="たてる", is_content=True, is_yougen=True,
             accents=(2,))
    assert u.surface == "立て"
    assert u.accents == (2,)
    assert u.ambiguous is False  # default
    import dataclasses
    with __import__("pytest").raises(dataclasses.FrozenInstanceError):
        u.surface = "x"  # type: ignore[misc]

def test_bare_unit_has_no_accents():
    u = Unit(surface="は", lemma="は", reading="は",
             lemma_reading="は", is_content=False, is_yougen=False)
    assert u.accents == ()
    assert u.is_content is False
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --group dev pytest tests/test_jrp_unit.py -q`
Expected: FAIL with `ModuleNotFoundError: ankialign.jrp_fill.unit`.

- [ ] **Step 3: Write minimal implementation**

Create `ankialign/jrp_fill/unit.py`:

```python
"""The Unit: one token's worth of data passed from tokenize to emit."""
from __future__ import annotations
from dataclasses import dataclass, field

@dataclass(frozen=True)
class Unit:
    surface: str        # text as it appears, possibly inflected: 見逃し
    lemma: str          # dictionary form: 見逃す
    reading: str        # hiragana reading of the surface: みのがし
    lemma_reading: str  # hiragana reading of the lemma (accent key): みのがす
    is_content: bool    # False -> rendered as bare text (particles, punctuation)
    is_yougen: bool     # 動詞/形容詞 -> emit ;Y
    accents: tuple[int, ...] = field(default=())  # () -> furigana only, no pitch block
    ambiguous: bool = False
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --group dev pytest tests/test_jrp_unit.py -q`
Expected: PASS (2 passed).

- [ ] **Step 5: Commit**

```bash
git add ankialign/jrp_fill/unit.py tests/test_jrp_unit.py
git commit -m "feat(jrp): Unit dataclass (tokenize/emit interface)"
```

---

### Task 2: `align` — furigana segmentation (the crux)

Aligns a surface (kanji+kana) against an all-hiragana reading into segments. Only **kanji runs** receive readings; kana/other runs are always bare. On any mismatch, fall back to a single whole-surface segment (always round-trip safe).

**Files:**
- Create: `ankialign/jrp_fill/align.py`
- Test: `tests/test_jrp_align.py`

- [ ] **Step 1: Write the failing test**

Create `tests/test_jrp_align.py`:

```python
from ankialign.jrp_fill.align import furigana_segments as fs

def test_pure_kana_is_bare():
    assert fs("それ", "それ") == [("それ", None)]

def test_no_kanji_never_gets_furigana():
    assert fs("チャイナ", "ちゃいな") == [("チャイナ", None)]

def test_leading_kanji_with_okurigana():
    assert fs("食べる", "たべる") == [("食", "た"), ("べる", None)]

def test_compound_kanji_run():
    assert fs("気付", "きづ") == [("気付", "きづ")]
    assert fs("気付く", "きづく") == [("気付", "きづ"), ("く", None)]

def test_kanji_kana_kanji_kana():
    assert fs("持ち掛ける", "もちかける") == [
        ("持", "も"), ("ち", None), ("掛", "か"), ("ける", None)]

def test_trailing_kanji_takes_rest():
    assert fs("お茶", "おちゃ") == [("お", None), ("茶", "ちゃ")]

def test_mismatch_falls_back_to_whole_segment():
    # reading that cannot be aligned -> single segment, still round-trips
    assert fs("大人気", "だいにんき") == [("大人気", "だいにんき")]

def test_empty_reading_is_bare():
    assert fs("！", "") == [("！", None)]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --group dev pytest tests/test_jrp_align.py -q`
Expected: FAIL with `ModuleNotFoundError: ankialign.jrp_fill.align`.

- [ ] **Step 3: Write minimal implementation**

Create `ankialign/jrp_fill/align.py`:

```python
"""Align a surface string against its hiragana reading into furigana segments.

Returns a list of (text, reading|None). reading is None for kana/other runs
(rendered bare); a hiragana string for kanji runs. Any failure to align
cleanly returns a single (surface, reading) segment, which always round-trips.
"""
from __future__ import annotations
import re

# same kanji ranges as ankialign/markup.py, as a per-character class
_KANJI = re.compile(
    r"[㐀-鿿豈-﫿々\U00020000-\U0002A6DF\U0002A700-\U0002CEAF"
    r"\U0002CEB0-\U0002EBEF]"
)
_KATA = {chr(c): chr(c - 0x60) for c in range(0x30A1, 0x30F7)}

def _hira(s: str) -> str:
    return "".join(_KATA.get(c, c) for c in s)

def _is_kanji(ch: str) -> bool:
    return bool(_KANJI.match(ch))

def _runs(surface: str) -> list[tuple[bool, str]]:
    """Split into maximal (is_kanji, text) runs."""
    out: list[tuple[bool, str]] = []
    for ch in surface:
        k = _is_kanji(ch)
        if out and out[-1][0] == k:
            out[-1] = (k, out[-1][1] + ch)
        else:
            out.append((k, ch))
    return out

def furigana_segments(surface: str, reading: str) -> list[tuple[str, str | None]]:
    if not surface:
        return []
    runs = _runs(surface)
    if not any(k for k, _ in runs):           # no kanji at all -> all bare
        return [(surface, None)]

    hread = _hira(reading)
    segs: list[tuple[str, str | None]] = []
    ri = 0
    for i, (is_k, text) in enumerate(runs):
        if not is_k:
            anchor = _hira(text)
            if hread[ri:ri + len(anchor)] != anchor:
                return [(surface, reading)]    # mismatch -> safe fallback
            segs.append((text, None))
            ri += len(anchor)
        else:
            if i + 1 < len(runs):              # next run is kana: find it to delimit
                nxt = _hira(runs[i + 1][1])
                j = hread.find(nxt, ri)
                if j < 0 or j == ri:
                    return [(surface, reading)]
                segs.append((text, hread[ri:j]))
                ri = j
            else:                              # last run: take the rest
                if ri >= len(hread):
                    return [(surface, reading)]
                segs.append((text, hread[ri:]))
                ri = len(hread)
    if ri != len(hread):                       # leftover reading -> fallback
        return [(surface, reading)]
    return segs
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --group dev pytest tests/test_jrp_align.py -q`
Expected: PASS (8 passed).

- [ ] **Step 5: Commit**

```bash
git add ankialign/jrp_fill/align.py tests/test_jrp_align.py
git commit -m "feat(jrp): furigana alignment with safe whole-segment fallback"
```

---

### Task 3: `emit` — Unit list → JRP markup string

**Files:**
- Create: `ankialign/jrp_fill/emit.py`
- Test: `tests/test_jrp_emit.py`

- [ ] **Step 1: Write the failing test**

Create `tests/test_jrp_emit.py`:

```python
from ankialign.jrp_fill.unit import Unit
from ankialign.jrp_fill.emit import emit_unit, emit_sentence
from ankialign import markup

def U(**kw):
    base = dict(surface="", lemma="", reading="", lemma_reading="",
                is_content=True, is_yougen=False, accents=(), ambiguous=False)
    base.update(kw)
    return Unit(**base)

def test_bare_particle():
    assert emit_unit(U(surface="は", reading="は", is_content=False)) == "は"

def test_kanji_no_accent_gets_furigana_only():
    u = U(surface="異端", reading="いたん", lemma="異端", lemma_reading="いたん", accents=())
    assert emit_unit(u) == "[異端|いたん]"

def test_kanji_with_accent_is_wrapped():
    u = U(surface="異変", reading="いへん", lemma="異変", lemma_reading="いへん", accents=(0,))
    assert emit_unit(u) == "{[異変|いへん];0}"

def test_yougen_flag_and_okurigana():
    u = U(surface="気付いた", reading="きづいた", lemma="気付く",
          lemma_reading="きづく", is_yougen=True, accents=(2,))
    assert emit_unit(u) == "{[気付|きづ]いた;Y2}"

def test_multiple_accents():
    u = U(surface="世界", reading="せかい", lemma="世界",
          lemma_reading="せかい", accents=(1, 2))
    assert emit_unit(u) == "{[世界|せかい];1,2}"

def test_kana_content_with_accent():
    u = U(surface="それ", reading="それ", lemma="それ", lemma_reading="それ", accents=(1,))
    assert emit_unit(u) == "{それ;1}"

def test_emit_sentence_roundtrips_surface():
    units = [
        U(surface="異変", reading="いへん", lemma="異変", lemma_reading="いへん", accents=(0,)),
        U(surface="に", reading="に", is_content=False),
        U(surface="気付いた", reading="きづいた", lemma="気付く",
          lemma_reading="きづく", is_yougen=True, accents=(2,)),
    ]
    out = emit_sentence(units)
    assert out == "{[異変|いへん];0}に{[気付|きづ]いた;Y2}"
    assert markup.jrp_surface(out) == "異変に気付いた"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --group dev pytest tests/test_jrp_emit.py -q`
Expected: FAIL with `ModuleNotFoundError: ankialign.jrp_fill.emit`.

- [ ] **Step 3: Write minimal implementation**

Create `ankialign/jrp_fill/emit.py`:

```python
"""Render Unit lists into jidoujisho/JRP default-syntax markup."""
from __future__ import annotations
from .unit import Unit
from .align import furigana_segments

def _furi(surface: str, reading: str) -> str:
    segs = furigana_segments(surface, reading)
    return "".join(f"[{b}|{r}]" if r else b for b, r in segs)

def emit_unit(u: Unit) -> str:
    if not u.is_content:
        return u.surface
    body = _furi(u.surface, u.reading)
    if u.accents:
        flag = ("!" if u.ambiguous else "") + ("Y" if u.is_yougen else "")
        acc = ",".join(str(a) for a in u.accents)
        return "{" + body + ";" + flag + acc + "}"
    return body

def emit_sentence(units: list[Unit]) -> str:
    return "".join(emit_unit(u) for u in units)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --group dev pytest tests/test_jrp_emit.py -q`
Expected: PASS (7 passed).

- [ ] **Step 5: Commit**

```bash
git add ankialign/jrp_fill/emit.py tests/test_jrp_emit.py
git commit -m "feat(jrp): emit Unit lists to JRP default-syntax markup"
```

---

### Task 4: `splice` — fill plain runs, never touch existing markup

Walks a sentence, keeping existing `{...}` and `[...]` spans verbatim, and calling an injected `generate(plain)->markup` on plain runs **that contain kanji** (kana-only/punctuation runs are kept as-is). This handles all three cases: fully-plain (one big plain run), partial (gaps only), fully-marked (no kanji-bearing plain runs → no-op).

**Files:**
- Create: `ankialign/jrp_fill/splice.py`
- Test: `tests/test_jrp_splice.py`

- [ ] **Step 1: Write the failing test**

Create `tests/test_jrp_splice.py`:

```python
from ankialign.jrp_fill.splice import splice

def gen(text):  # stub generator: wraps kanji-bearing runs distinctly
    return "<<" + text + ">>"

def test_fully_plain_is_one_run():
    assert splice("異端である", gen) == "<<異端である>>"

def test_kana_only_run_is_untouched():
    assert splice("そうだね", gen) == "そうだね"

def test_existing_unit_is_preserved():
    s = "{[異変|いへん];0}に気付いた"
    assert splice(s, gen) == "{[異変|いへん];0}に<<気付いた>>"

def test_bracket_segment_is_preserved():
    s = "[気付|きづ]いて異端"
    assert splice(s, gen) == "[気付|きづ]いて<<異端>>"

def test_fully_marked_is_noop():
    s = "{[異変|いへん];0}に{[気付|きづ]いた;Y2}"
    assert splice(s, gen) == s
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --group dev pytest tests/test_jrp_splice.py -q`
Expected: FAIL with `ModuleNotFoundError: ankialign.jrp_fill.splice`.

- [ ] **Step 3: Write minimal implementation**

Create `ankialign/jrp_fill/splice.py`:

```python
"""Splice generated markup into a sentence, preserving existing JRP spans.

Existing `{...}` units and `[...]` furigana segments are kept byte-for-byte.
Plain runs containing at least one kanji are passed to `generate`; kana-only
or punctuation-only runs are left untouched.
"""
from __future__ import annotations
import re
from collections.abc import Callable

_KANJI = re.compile(
    r"[㐀-鿿豈-﫿々\U00020000-\U0002A6DF\U0002A700-\U0002CEAF"
    r"\U0002CEB0-\U0002EBEF]"
)

def _spans(s: str) -> list[tuple[str, str]]:
    """Tokenize into ('markup', text) for {...}/[...] and ('plain', text) runs."""
    out: list[tuple[str, str]] = []
    i, n = 0, len(s)
    buf: list[str] = []
    def flush():
        if buf:
            out.append(("plain", "".join(buf)))
            buf.clear()
    while i < n:
        c = s[i]
        close = "}" if c == "{" else ("]" if c == "[" else None)
        if close:
            j = s.find(close, i)
            if j >= 0:
                flush()
                out.append(("markup", s[i:j + 1]))
                i = j + 1
                continue
        buf.append(c)
        i += 1
    flush()
    return out

def splice(sentence: str, generate: Callable[[str], str]) -> str:
    parts: list[str] = []
    for kind, text in _spans(sentence):
        if kind == "plain" and _KANJI.search(text):
            parts.append(generate(text))
        else:
            parts.append(text)
    return "".join(parts)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --group dev pytest tests/test_jrp_splice.py -q`
Expected: PASS (5 passed).

- [ ] **Step 5: Commit**

```bash
git add ankialign/jrp_fill/splice.py tests/test_jrp_splice.py
git commit -m "feat(jrp): splice markup into plain runs, preserving existing spans"
```

---

### Task 5: `build_accent_index.py` — build + ship the accent index

Builds `ankialign/data/accent_index.marshal.gz` keyed `"lemma\treading_hira" -> [positions]`, layering NHK2016 → アクセント辞典v2 → gold so gold wins. Reuses the spike's `validate.load_dict` / `validate.extract_gold`.

**Files:**
- Create: `research/jrp-pipeline/build_accent_index.py`
- Create (artifact): `ankialign/data/accent_index.marshal.gz`

- [ ] **Step 1: Write the builder**

Create `research/jrp-pipeline/build_accent_index.py`:

```python
#!/usr/bin/env python3
"""Build ankialign/data/accent_index.marshal.gz.

Key:   "lemma\treading_hira"  Value: sorted list[int] of downstep positions.
Source priority (later overwrites): NHK2016 -> アクセント辞典v2 -> user gold.
Run:   uv run python research/jrp-pipeline/build_accent_index.py
Reuses research/pitch-pipeline/work/validate.py.
"""
import os, sys, gzip, marshal

ROOT = os.path.expanduser("~/projects/anki-align")
sys.path.insert(0, os.path.join(ROOT, "research/pitch-pipeline/work"))
import validate as V

KATA = {chr(c): chr(c - 0x60) for c in range(0x30A1, 0x30F7)}
def hira(s): return "".join(KATA.get(c, c) for c in s)
def key(lemma, reading): return lemma + "\t" + hira(reading)

def main():
    index: dict[str, list[int]] = {}
    # NHK2016 then v2 (dict term_meta): (expr,reading)->set positions
    for name in ("NHK 2016 (Recommended)", "アクセント辞典v2"):
        m = V.load_dict(os.path.join(V.DICTD, name))
        for (expr, reading), positions in m.items():
            index[key(expr, reading)] = sorted(positions)
        print(f"after {name}: {len(index)} keys", file=sys.stderr)
    # gold wins
    gold, _, _ = V.extract_gold()
    for (expr, reading), (_yg, accs) in gold.items():
        index[key(expr, reading)] = sorted(accs)
    print(f"after gold: {len(index)} keys", file=sys.stderr)

    out = os.path.join(ROOT, "ankialign/data/accent_index.marshal.gz")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with gzip.open(out, "wb") as fh:
        fh.write(marshal.dumps(index))
    print(f"wrote {out} ({os.path.getsize(out)} bytes, {len(index)} keys)", file=sys.stderr)

if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Run the builder and verify counts**

Run: `cd ~/projects/anki-align && uv run python research/jrp-pipeline/build_accent_index.py`
Expected: stderr shows roughly `after NHK 2016 ...`, `after アクセント辞典v2 ...` (>300k keys), `after gold ...`, and `wrote .../accent_index.marshal.gz`. Confirm the file exists:
Run: `ls -la ankialign/data/accent_index.marshal.gz`
Expected: a multi-MB file.

- [ ] **Step 3: Commit (builder + artifact)**

```bash
git add research/jrp-pipeline/build_accent_index.py ankialign/data/accent_index.marshal.gz
git commit -m "feat(jrp): build + ship accent index (gold>v2>NHK2016)"
```

---

### Task 6: `accent.py` — load + look up the shipped index

**Files:**
- Create: `ankialign/jrp_fill/accent.py`
- Test: `tests/test_jrp_accent.py`

- [ ] **Step 1: Write the failing test**

Create `tests/test_jrp_accent.py` (skips if the artifact is absent):

```python
import os
import pytest
from ankialign.jrp_fill.accent import AccentIndex

IDX = os.path.join(os.path.dirname(__file__), "..",
                   "ankialign", "data", "accent_index.marshal.gz")
pytestmark = pytest.mark.skipif(not os.path.exists(IDX), reason="no accent index")

def test_known_word_returns_positions():
    acc = AccentIndex.load(IDX)
    # 世界(せかい) is a high-frequency entry; expect a non-empty position list
    pos = acc.lookup("世界", "せかい")
    assert isinstance(pos, list) and len(pos) >= 1

def test_unknown_returns_none():
    acc = AccentIndex.load(IDX)
    assert acc.lookup("ボンクラ存在しない語", "ぼんくらそんざいしないご") is None

def test_default_path_loads():
    acc = AccentIndex.load()
    assert acc.lookup("世界", "せかい") is not None
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --group dev pytest tests/test_jrp_accent.py -q`
Expected: FAIL with `ModuleNotFoundError: ankialign.jrp_fill.accent`.

- [ ] **Step 3: Write minimal implementation**

Create `ankialign/jrp_fill/accent.py`:

```python
"""Load and query the prebuilt accent index (source priority baked in)."""
from __future__ import annotations
import os, gzip, marshal

_DEFAULT = os.path.join(os.path.dirname(__file__), "..", "data", "accent_index.marshal.gz")

class AccentIndex:
    def __init__(self, table: dict[str, list[int]]):
        self._t = table

    @classmethod
    def load(cls, path: str | None = None) -> "AccentIndex":
        path = path or _DEFAULT
        with gzip.open(path, "rb") as fh:
            return cls(marshal.loads(fh.read()))

    def lookup(self, lemma: str, reading_hira: str) -> list[int] | None:
        return self._t.get(lemma + "\t" + reading_hira)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --group dev pytest tests/test_jrp_accent.py -q`
Expected: PASS (3 passed).

- [ ] **Step 5: Commit**

```bash
git add ankialign/jrp_fill/accent.py tests/test_jrp_accent.py
git commit -m "feat(jrp): AccentIndex load/lookup over shipped index"
```

---

### Task 7: `tokenize.py` — Sudachi Mode C → Unit list

Mode C tokenizer (lazy singleton), lemma-form reading for the accent key, and the Mode C→A split-and-retry cascade. Marine is an optional injected callback (Task 11); here we pass `marine=None`.

**Files:**
- Create: `ankialign/jrp_fill/tokenize.py`
- Test: `tests/test_jrp_tokenize.py`

- [ ] **Step 1: Write the failing test** (requires the `jrp` group + accent index)

Create `tests/test_jrp_tokenize.py`:

```python
import os, shutil
import pytest

IDX = os.path.join(os.path.dirname(__file__), "..",
                   "ankialign", "data", "accent_index.marshal.gz")
pytestmark = pytest.mark.skipif(
    shutil.which("python") is None or not os.path.exists(IDX),
    reason="needs accent index")

def _sudachi_available():
    try:
        import sudachipy  # noqa: F401
        return True
    except Exception:
        return False

@pytest.mark.skipif(not _sudachi_available(), reason="sudachipy not installed")
def test_units_roundtrip_and_accent():
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    from ankialign import markup
    acc = AccentIndex.load(IDX)
    s = "異世界には魔物がいる"
    us = units(s, acc)
    out = emit_sentence(us)
    # round-trips to the original surface
    assert markup.jrp_surface(out) == s
    # 異世界 is a compound that Mode C should keep whole and pitch
    assert "異世界" in out and "{" in out

@pytest.mark.skipif(not _sudachi_available(), reason="sudachipy not installed")
def test_split_cascade_recovers_overmerge():
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    from ankialign import markup
    acc = AccentIndex.load(IDX)
    out = emit_sentence(units("前途多難だ", acc))
    assert markup.jrp_surface(out) == "前途多難だ"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --group dev --group jrp pytest tests/test_jrp_tokenize.py -q`
Expected: FAIL with `ModuleNotFoundError: ankialign.jrp_fill.tokenize`.

- [ ] **Step 3: Write minimal implementation**

Create `ankialign/jrp_fill/tokenize.py`:

```python
"""SudachiPy Mode C tokenization -> Unit list, with lemma-reading accent
lookup and a Mode C -> Mode A split-and-retry cascade.

`marine` is an optional callable: marine(Unit) -> tuple[int, ...] | None,
used only when dict lookup (and the split cascade) miss.
"""
from __future__ import annotations
from functools import lru_cache
from collections.abc import Callable
from .unit import Unit
from .accent import AccentIndex

_KATA = {chr(c): chr(c - 0x60) for c in range(0x30A1, 0x30F7)}
def _hira(s: str) -> str:
    return "".join(_KATA.get(c, c) for c in s)

_CONTENT = {"名詞", "動詞", "形容詞", "副詞", "形状詞", "連体詞", "感動詞"}
_YOUGEN = {"動詞", "形容詞"}

@lru_cache(maxsize=1)
def _tok():
    from sudachipy import dictionary
    return dictionary.Dictionary(dict="full").create()

@lru_cache(maxsize=1)
def _modes():
    from sudachipy import tokenizer as t
    return t.Tokenizer.SplitMode.A, t.Tokenizer.SplitMode.C

@lru_cache(maxsize=4096)
def _lemma_reading(lemma: str) -> str:
    _, C = _modes()
    ms = _tok().tokenize(lemma, C)
    return _hira("".join(m.reading_form() or m.surface() for m in ms))

def _to_unit(m, acc: AccentIndex) -> Unit:
    pos0 = m.part_of_speech()[0]
    surface = m.surface()
    reading = _hira(m.reading_form() or surface)
    lemma = m.dictionary_form() or surface
    is_content = pos0 in _CONTENT
    if not is_content:
        return Unit(surface, lemma, reading, reading, False, False, ())
    lemread = _lemma_reading(lemma)
    positions = acc.lookup(lemma, lemread) or acc.lookup(lemma, reading)
    return Unit(surface, lemma, reading, lemread, True, pos0 in _YOUGEN,
                tuple(positions) if positions else ())

def units(text: str, acc: AccentIndex,
          marine: Callable[[Unit], "tuple[int, ...] | None"] | None = None) -> list[Unit]:
    A, C = _modes()
    out: list[Unit] = []
    for m in _tok().tokenize(text, C):
        u = _to_unit(m, acc)
        if u.is_content and not u.accents:
            subs = list(m.split(A))
            if len(subs) > 1:
                sub_us = [_to_unit(x, acc) for x in subs]
                if all((not su.is_content) or su.accents for su in sub_us):
                    out.extend(sub_us)
                    continue
            if marine is not None:
                got = marine(u)
                if got:
                    u = Unit(u.surface, u.lemma, u.reading, u.lemma_reading,
                             u.is_content, u.is_yougen, tuple(got))
        out.append(u)
    return out
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --group dev --group jrp pytest tests/test_jrp_tokenize.py -q`
Expected: PASS (2 passed). First run downloads sudachidict-full (slow, ~minutes).

- [ ] **Step 5: Commit**

```bash
git add ankialign/jrp_fill/tokenize.py tests/test_jrp_tokenize.py
git commit -m "feat(jrp): Sudachi Mode C tokenizer with lemma-reading + split cascade"
```

---

### Task 8: `engine.py` — plan + apply over an Anki collection

Selects target notes, generates markup via `splice(sentence, generate)` where `generate = lambda t: emit_sentence(units(t, acc, marine))`, applies the round-trip guard, and writes through `col.update_notes`. Uses the `anki` library (already a dev dep).

**Files:**
- Create: `ankialign/jrp_fill/engine.py`
- Test: `tests/test_jrp_engine.py`

- [ ] **Step 1: Write the failing test** (builds a tiny temp collection; stubs the generator so it needs no Sudachi)

Create `tests/test_jrp_engine.py`:

```python
import os, pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankialign.jrp_fill import engine

def _make_col(tmp_path):
    col = Collection(str(tmp_path / "col.anki2"))
    m = col.models.new("jidoujisho Kinomoto")
    for f in ("Term", "Sentence"):
        col.models.add_field(m, col.models.new_field(f))
    tmpl = col.models.new_template("Card 1")
    tmpl["qfmt"] = "{{Sentence}}"; tmpl["afmt"] = "{{Sentence}}"
    col.models.add_template(m, tmpl)
    col.models.add(m)
    return col, m

def _add(col, m, term, sentence):
    n = col.new_note(m)
    n["Term"] = term; n["Sentence"] = sentence
    col.add_note(n, col.decks.id("Default"))
    return n.id

def test_plan_classifies_and_generates(tmp_path):
    col, m = _make_col(tmp_path)
    plain = _add(col, m, "異端", "異端である")
    marked = _add(col, m, "異変", "{[異変|いへん];0}だ")
    empty = _add(col, m, "x", "")
    # stub generate: wrap kanji-bearing plain runs
    plan = engine.build_plan(col, "jidoujisho Kinomoto", "Sentence",
                             generate=lambda t: "<<" + t + ">>")
    by_id = {p.note_id: p for p in plan}
    assert by_id[plain].new_value == "<<異端である>>"
    assert marked not in by_id            # fully marked -> no change -> skipped
    assert empty not in by_id             # empty -> skipped
    col.close()

def test_apply_writes_through_col(tmp_path):
    col, m = _make_col(tmp_path)
    nid = _add(col, m, "異端", "異端である")
    plan = engine.build_plan(col, "jidoujisho Kinomoto", "Sentence",
                             generate=lambda t: "<<" + t + ">>")
    engine.apply_plan(col, plan)
    assert col.get_note(nid)["Sentence"] == "<<異端である>>"
    col.close()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --group dev pytest tests/test_jrp_engine.py -q`
Expected: FAIL with `ModuleNotFoundError: ankialign.jrp_fill.engine`.

- [ ] **Step 3: Write minimal implementation**

Create `ankialign/jrp_fill/engine.py`:

```python
"""Plan + apply JRP sentence fills over an opened Anki collection."""
from __future__ import annotations
from dataclasses import dataclass
from collections.abc import Callable
from ankialign import markup
from .splice import splice

@dataclass(frozen=True)
class Fill:
    note_id: int
    old_value: str
    new_value: str

def _norm(s: str) -> str:
    return s.replace("<br>", " ").replace("<br/>", " ").replace(" ", " ")

def build_plan(col, note_type: str, field: str,
               generate: Callable[[str], str]) -> list[Fill]:
    """Generate the diff. `generate(plain_text) -> markup` is injected so this
    function stays testable without Sudachi."""
    mid = col.models.id_for_name(note_type)
    if mid is None:
        return []
    model = col.models.get(mid)
    names = [f["name"] for f in model["flds"]]
    fi = names.index(field)
    plan: list[Fill] = []
    for nid in col.models.nids(model):
        note = col.get_note(nid)
        old = note.fields[fi]
        if not old.strip():
            continue
        new = splice(old, generate)
        if new == old:
            continue
        # round-trip guard: visible surface must be unchanged
        if _norm(markup.jrp_surface(new)) != _norm(markup.jrp_surface(old)):
            continue
        plan.append(Fill(nid, old, new))
    return plan

def apply_plan(col, plan: list[Fill], field: str = "Sentence") -> int:
    notes = []
    for fill in plan:
        note = col.get_note(fill.note_id)
        fi = note.keys().index(field)
        note.fields[fi] = fill.new_value
        notes.append(note)
    if notes:
        col.update_notes(notes)
    return len(notes)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --group dev pytest tests/test_jrp_engine.py -q`
Expected: PASS (2 passed).

- [ ] **Step 5: Commit**

```bash
git add ankialign/jrp_fill/engine.py tests/test_jrp_engine.py
git commit -m "feat(jrp): collection plan/apply engine with round-trip guard"
```

---

### Task 9: `cli.py` — dry-run / apply with forced backup

Wires Sudachi + accent index into `generate`, opens the collection, prints a dry-run diff or applies after a timestamped file backup.

**Files:**
- Create: `ankialign/jrp_fill/__main__.py`
- Create: `ankialign/jrp_fill/cli.py`
- Test: manual smoke (collection is gitignored).

- [ ] **Step 1: Write the CLI**

Create `ankialign/jrp_fill/cli.py`:

```python
"""`uv run --group jrp python -m ankialign.jrp_fill [--apply] [--marine]`"""
from __future__ import annotations
import argparse, os, shutil, sys, time

def _generate_factory(marine_enabled: bool):
    from .accent import AccentIndex
    from .tokenize import units
    from .emit import emit_sentence
    acc = AccentIndex.load()
    marine = None
    if marine_enabled:
        from .marine_tail import marine_fill
        marine = marine_fill
    return lambda text: emit_sentence(units(text, acc, marine))

def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="ankialign.jrp_fill")
    ap.add_argument("--collection", default=os.path.expanduser(
        "~/projects/anki-align/collection.anki2"))
    ap.add_argument("--note-type", default="jidoujisho Kinomoto")
    ap.add_argument("--field", default="Sentence")
    ap.add_argument("--apply", action="store_true", help="write (default: dry-run)")
    ap.add_argument("--marine", action="store_true", help="enable marine fallback")
    ap.add_argument("--limit", type=int, default=0, help="cap notes for spot-checking")
    args = ap.parse_args(argv)

    if not os.path.exists(args.collection):
        print(f"no collection at {args.collection}", file=sys.stderr); return 1

    from anki.collection import Collection
    from . import engine

    if args.apply:
        bak = f"{args.collection}.{time.strftime('%Y%m%d-%H%M%S')}.bak"
        shutil.copy2(args.collection, bak)
        print(f"backup -> {bak}")

    col = Collection(args.collection)
    try:
        gen = _generate_factory(args.marine)
        plan = engine.build_plan(col, args.note_type, args.field, gen)
        if args.limit:
            plan = plan[:args.limit]
        print(f"{len(plan)} notes would change")
        for fill in plan[:15]:
            print(f"\n  OLD {fill.old_value[:80]}\n  NEW {fill.new_value[:80]}")
        if args.apply:
            n = engine.apply_plan(col, plan, args.field)
            col.save()
            print(f"applied {n}")
        else:
            print("\n(dry-run; pass --apply to write)")
    finally:
        col.close()
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
```

Create `ankialign/jrp_fill/__main__.py`:

```python
from ankialign.jrp_fill.cli import main
raise SystemExit(main())
```

- [ ] **Step 2: Smoke-test the dry run on a small limit** (Anki must be closed)

Run: `cd ~/projects/anki-align && uv run --group dev --group jrp python -m ankialign.jrp_fill --limit 5`
Expected: prints "N notes would change" and up to 5 OLD/NEW pairs; exits 0; no file written.

- [ ] **Step 3: Verify a generated sample round-trips by eye**

Inspect the printed NEW lines: each should be the OLD sentence with `{[kanji|kana];acc}` units added, and `markup.jrp_surface(NEW)` would equal OLD (already enforced by the engine guard).

- [ ] **Step 4: Commit**

```bash
git add ankialign/jrp_fill/cli.py ankialign/jrp_fill/__main__.py
git commit -m "feat(jrp): CLI with dry-run/apply + forced backup"
```

---

### Task 10: Ground-truth regression gate

Re-runs the spike's strict-coverage measurement as a pytest gate (skipped when the collection is absent). Locks in ≥98% strict (term,reading) accent coverage on a fixed sample so dict/tokenizer regressions are caught.

**Files:**
- Create: `tests/test_jrp_groundtruth.py`

- [ ] **Step 1: Write the test**

Create `tests/test_jrp_groundtruth.py`:

```python
import os, random
import pytest

DB = os.path.join(os.path.dirname(__file__), "..", "collection.anki2")
IDX = os.path.join(os.path.dirname(__file__), "..",
                   "ankialign", "data", "accent_index.marshal.gz")

def _sudachi():
    try:
        import sudachipy  # noqa: F401
        return True
    except Exception:
        return False

pytestmark = pytest.mark.skipif(
    not (os.path.exists(DB) and os.path.exists(IDX) and _sudachi()),
    reason="needs collection + accent index + sudachipy")

def test_strict_accent_coverage_at_least_98pct():
    import sqlite3
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    acc = AccentIndex.load(IDX)
    con = sqlite3.connect(f"file:{DB}?immutable=1", uri=True)
    con.create_collation("unicase",
        lambda a, b: (a.casefold() > b.casefold()) - (a.casefold() < b.casefold()))
    mid = con.execute(
        "SELECT id FROM notetypes WHERE name='jidoujisho Kinomoto'").fetchone()[0]
    names = [r[0] for r in con.execute(
        "SELECT name FROM fields WHERE ntid=? ORDER BY ord", (mid,))]
    si = names.index("Sentence")
    plain = []
    for (flds,) in con.execute("SELECT flds FROM notes WHERE mid=?", (mid,)):
        s = flds.split("\x1f")[si]
        if s.strip() and not ("{" in s and ";" in s):
            plain.append(s.replace("<br>", " "))
    random.seed(7)
    sample = random.sample(plain, min(200, len(plain)))
    total = hit = 0
    for s in sample:
        for u in units(s, acc):
            if u.is_content:
                total += 1
                if u.accents:
                    hit += 1
    assert total > 0
    cov = hit / total
    assert cov >= 0.98, f"strict accent coverage regressed: {cov:.3f} ({hit}/{total})"
```

- [ ] **Step 2: Run the gate**

Run: `cd ~/projects/anki-align && uv run --group dev --group jrp pytest tests/test_jrp_groundtruth.py -q`
Expected: PASS (coverage ≥ 0.98), or SKIP if the collection is absent.

- [ ] **Step 3: Run the whole suite**

Run: `uv run --group dev --group jrp pytest -q`
Expected: all pass (sudachi/collection-dependent tests may skip in minimal envs).

- [ ] **Step 4: Commit**

```bash
git add tests/test_jrp_groundtruth.py
git commit -m "test(jrp): ground-truth strict-coverage regression gate (>=98%)"
```

---

### Task 11 (optional, last): `marine_tail.py` — neural fallback for misses

Adds the `marine` fallback used only when dict lookup + split cascade miss (~1.2%). Heavy PyTorch dep; lazy-imported and off by default. Skip this task if you want to ship dict-only first.

**Files:**
- Modify: `pyproject.toml` (add `marine` group)
- Create: `ankialign/jrp_fill/marine_tail.py`
- Test: `tests/test_jrp_marine.py`

- [ ] **Step 1: Add the optional group**

Edit `pyproject.toml` `[dependency-groups]`, add:

```toml
marine = ["marine>=0.0.6"]
```

- [ ] **Step 2: Write the failing test** (skips unless marine installed)

Create `tests/test_jrp_marine.py`:

```python
import pytest

def _marine():
    try:
        import marine  # noqa: F401
        return True
    except Exception:
        return False

@pytest.mark.skipif(not _marine(), reason="marine not installed")
def test_marine_fill_returns_positions_or_none():
    from ankialign.jrp_fill.unit import Unit
    from ankialign.jrp_fill.marine_tail import marine_fill
    u = Unit("誹謗中傷", "誹謗中傷", "ひぼうちゅうしょう", "ひぼうちゅうしょう",
             True, False, ())
    got = marine_fill(u)
    assert got is None or (isinstance(got, tuple) and all(isinstance(x, int) for x in got))
```

- [ ] **Step 3: Run test to verify it fails**

Run: `uv run --group dev pytest tests/test_jrp_marine.py -q`
Expected: SKIP (marine not installed) or FAIL (module missing) once the group is installed.

- [ ] **Step 4: Write minimal implementation**

Create `ankialign/jrp_fill/marine_tail.py`:

```python
"""Optional marine neural-accent fallback for dict misses.

marine predicts per-mora accent_status + accent nucleus from morpheme nodes;
we feed the single unit's reading and return the nucleus position (downstep
mora index, 0 if heiban), or None on failure. Heavy: lazy import only.
"""
from __future__ import annotations
from functools import lru_cache
from .unit import Unit

@lru_cache(maxsize=1)
def _predictor():
    from marine.predict import Predictor
    return Predictor()

def marine_fill(u: Unit) -> "tuple[int, ...] | None":
    try:
        pred = _predictor()
        # marine expects tokenized nodes; for a single unit we hand it the
        # surface as one node and read back accent_status over its morae.
        result = pred.predict([[{"surface": u.surface, "pron": u.lemma_reading,
                                 "pos": "名詞,一般,*,*", "c_type": "*",
                                 "c_form": "*", "accent_type": 0,
                                 "accent_con_type": "*", "chain_flag": -1}]])
        status = result["accent_status"][0]
        if isinstance(status, int):
            return (status,)
        # status as per-mora 0/1: nucleus = index after the last 1->0 drop
        if isinstance(status, (list, tuple)):
            for i in range(len(status) - 1):
                if status[i] == 1 and status[i + 1] == 0:
                    return (i + 1,)
            return (0,)
    except Exception:
        return None
    return None
```

> Note: marine's exact return shape varies by version; verify against the installed
> `marine` API during this task and adjust the parsing of `result` accordingly. The
> contract this module must honor is fixed: `marine_fill(Unit) -> tuple[int,...] | None`.

- [ ] **Step 5: Run test**

Run: `uv run --group dev --group marine pytest tests/test_jrp_marine.py -q`
Expected: PASS or SKIP.

- [ ] **Step 6: Re-run the ground-truth gate with marine enabled (optional)**

Run: `cd ~/projects/anki-align && uv run --group dev --group jrp --group marine python -m ankialign.jrp_fill --marine --limit 5`
Expected: dry-run runs; the ~1.2% dict-miss content units now get positions where marine is confident.

- [ ] **Step 7: Commit**

```bash
git add pyproject.toml ankialign/jrp_fill/marine_tail.py tests/test_jrp_marine.py
git commit -m "feat(jrp): optional marine neural fallback for dict misses"
```

---

## Final verification (after all tasks)

- [ ] Run the full suite: `uv run --group dev --group jrp pytest -q` — all pass/skip, none fail.
- [ ] Dry-run the real collection (Anki closed): `uv run --group dev --group jrp python -m ankialign.jrp_fill` — sanity-check the reported count is on the order of ~1,751 plain + partial fills and spot-check 15 NEW samples.
- [ ] When satisfied, apply: `uv run --group dev --group jrp python -m ankialign.jrp_fill --apply`, then open Anki and confirm a few cards render furigana + pitch.
- [ ] Use `superpowers:finishing-a-development-branch` to merge `feat/jrp-sentence-fill`.

## Spec coverage map

- Build-our-own / standalone offline CLI → Tasks 0, 9.
- Sudachi Mode C + lemma-reading + split cascade → Task 7 (validated by Task 10).
- Accent chain gold→v2→NHK2016 → Task 5 (build) + Task 6 (lookup); marine → Task 11.
- Furigana alignment / emit / multiple positions / `;Y` → Tasks 2, 3.
- Plain + partial scope, splice safety, fully-marked no-op → Task 4 + Task 8.
- Round-trip guard, `anki`-library writes, forced backup → Tasks 8, 9.
- Ground-truth ≥98% gate, pure unit tests → Tasks 1–4, 10.
- Deferred (noted): `deinflect.py`, `!` ambiguity mark — see header.
