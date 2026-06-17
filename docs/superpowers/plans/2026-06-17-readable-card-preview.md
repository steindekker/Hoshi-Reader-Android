# Readable Card Preview + Frequency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Telegram mining preview readable — format the dict definition into Telegram HTML, fold pitch + JPDB frequency into the header line — and use frequency to order the reading picker and fill the card's Frequency field.

**Architecture:** Add a frequency index (`ankienrich/freq/{build,index}.py` → `var/freq.sqlite`) mirroring the existing defs/audio/examples pattern (a `build()/ensure()` pair that is *skipped, not failed* when its source dict is absent). The mining flow's pure UI helpers (`mine.py`) gain an HTML→Telegram-HTML converter and a pitch/frequency-aware preview; `enrich_one` carries the accent numbers + frequency on `Fields` and sorts reading candidates by rank; `bot.py` opens the new index alongside the others.

**Tech Stack:** Python 3.11, `uv`, `sqlite3`, stdlib `html.parser`, `pytest`, `telebot` (HTML parse mode already set).

**Workflow note:** Per project convention this plan is throwaway scaffolding — **delete it once the suite is green.** No spec/design docs; durable "why" goes into terse docstrings only.

---

## File Structure

- **Create** `ankienrich/freq/__init__.py` — empty package marker.
- **Create** `ankienrich/freq/build.py` — JPDB `term_meta_bank_*.json` → `var/freq.sqlite`; `build()` + `ensure()`.
- **Create** `ankienrich/freq/index.py` — `open_index()` + `lookup(conn, term, reading)`.
- **Modify** `ankienrich/paths.py` — add `FREQ_DB`.
- **Modify** `ankienrich/config.py` — add `FREQUENCY = "Frequency"`.
- **Modify** `ankienrich/enrich_one.py` — `Candidate.freq`; `Fields.accents` + `Fields.freq`; `candidates()` freq sort; `build_fields()` freq lookup + accent passthrough.
- **Modify** `ankienrich/mine.py` — `tg_html()`, `pitch_text()`, rewritten `preview_text()`, `candidate_rows()` freq label, `create_card()` Frequency write.
- **Modify** `ankienrich/bot.py` — `_indexes()` opens/ensures freq; pass `freq_conn` at the call sites.
- **Modify** `tests/test_mine.py` — new/updated unit tests.
- **Create** `tests/test_freq.py` — freq build + index tests.
- **Setup (Task 0)** extract the JPDB zip into `dicts/jpdb/`.

---

### Task 0: Provide the JPDB frequency dictionary

**Files:**
- Create dir: `dicts/jpdb/` (extracted Yomitan dict — same as other dicts live extracted under `dicts/`).

- [ ] **Step 1: Extract the zip**

```bash
mkdir -p dicts/jpdb
unzip -o "$HOME/Downloads/JPDB_v2.2_Frequency_Kana_Display_Only_2024-10-13.zip" -d dicts/jpdb
```

- [ ] **Step 2: Verify the bank shape**

Run: `python3 -c "import json; d=json.load(open('dicts/jpdb/term_meta_bank_1.json')); print(d[0])"`
Expected: `['の', 'freq', {'value': 1, 'displayValue': '1㋕'}]`

- [ ] **Step 3: Confirm it is git-ignored like the other dicts**

Run: `git check-ignore dicts/jpdb/term_meta_bank_1.json && echo IGNORED || echo TRACKED`
Expected: `IGNORED` (dicts are build inputs, not committed). If it prints `TRACKED`, stop and ask — do **not** commit a 26 MB bank.

---

### Task 1: Frequency index — build

**Files:**
- Create: `ankienrich/freq/__init__.py`
- Create: `ankienrich/freq/build.py`
- Modify: `ankienrich/paths.py`
- Test: `tests/test_freq.py`

- [ ] **Step 1: Add the path constant**

In `ankienrich/paths.py`, after the `AUDIO_DB` line add:

```python
FREQ_DB = os.path.join(VAR_DIR, "freq.sqlite")
```

- [ ] **Step 2: Create the empty package marker**

Create `ankienrich/freq/__init__.py` with no content (empty file).

- [ ] **Step 3: Write the failing build test**

Create `tests/test_freq.py`:

```python
import json
from pathlib import Path

from ankienrich.freq import build as freq_build
from ankienrich.freq import index as freq_index


def _make_jpdb(dicts_dir: Path):
    """Minimal JPDB-shaped dict: surface + kana keys, rank-based freq meta."""
    d = dicts_dir / "jpdb"
    d.mkdir(parents=True)
    (d / "index.json").write_text(
        json.dumps({"title": "JPDBv2", "format": 3, "frequencyMode": "rank-based"}),
        encoding="utf-8")
    bank = [
        ["林檎", "freq", {"value": 1234, "displayValue": "1234"}],
        ["りんご", "freq", {"value": 1234, "displayValue": "1234㋕"}],
        ["箸", "freq", {"value": 50, "displayValue": "50"}],
        ["はし", "freq", {"value": 9, "displayValue": "9㋕"}],
    ]
    (d / "term_meta_bank_1.json").write_text(
        json.dumps(bank, ensure_ascii=False), encoding="utf-8")


def test_build_indexes_surface_and_reading(tmp_path):
    _make_jpdb(tmp_path)
    db = tmp_path / "freq.sqlite"
    n = freq_build.build(str(tmp_path), str(db))
    assert n == 4
    conn = freq_index.open_index(str(db))
    assert freq_index.lookup(conn, "林檎", "りんご") == (1234, "1234")
    conn.close()
```

- [ ] **Step 4: Run it to verify it fails**

Run: `uv run pytest tests/test_freq.py::test_build_indexes_surface_and_reading -v`
Expected: FAIL — `ModuleNotFoundError: ankienrich.freq.build` (or `AttributeError`).

- [ ] **Step 5: Implement the build**

Create `ankienrich/freq/build.py`:

```python
"""Build the frequency index (freq.sqlite) from a Yomitan rank-based frequency
dictionary (JPDB). BUILD-TIME input lives extracted under dicts/jpdb/; absent it
the index is skipped, not failed (deploys without it still run). Entries are
`[key, "freq", value]` where key is a surface OR kana headword — we index both so
a (term, reading) lookup can fall back from surface to reading."""
import json
import sqlite3
from pathlib import Path

DICT_ID = "jpdb"


def _banks(dicts_dir, dict_id) -> list[Path]:
    return sorted(Path(dicts_dir, dict_id).glob("term_meta_bank_*.json"))


def _value(meta) -> tuple[int, str] | None:
    """Normalise a Yomitan freq meta value to (rank, display). Handles the int,
    string, and {value, displayValue} forms; returns None for anything else."""
    if isinstance(meta, dict):
        v = meta.get("value")
        if isinstance(v, int):
            return v, str(meta.get("displayValue", v))
        return None
    if isinstance(meta, int):
        return meta, str(meta)
    if isinstance(meta, str) and meta.isdigit():
        return int(meta), meta
    return None


def _schema(conn):
    conn.executescript("""
        DROP TABLE IF EXISTS freq;
        CREATE TABLE freq (key TEXT PRIMARY KEY, value INTEGER, display TEXT);
    """)


def build(dicts_dir, db_path) -> int:
    """Write {key -> (rank, display)} into db_path; keep the smallest rank per key
    (most frequent wins on duplicates). Returns the row count."""
    from ankienrich import paths
    paths.ensure_var()
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    best: dict[str, tuple[int, str]] = {}
    for bank in _banks(dicts_dir, DICT_ID):
        for e in json.loads(bank.read_text(encoding="utf-8")):
            if len(e) < 3 or e[1] != "freq":
                continue
            parsed = _value(e[2])
            if parsed is None:
                continue
            key = e[0]
            if key not in best or parsed[0] < best[key][0]:
                best[key] = parsed
    if Path(db_path).exists():
        Path(db_path).unlink()
    conn = sqlite3.connect(db_path)
    _schema(conn)
    conn.executemany("INSERT INTO freq VALUES (?,?,?)",
                     ((k, v, d) for k, (v, d) in best.items()))
    conn.commit()
    conn.close()
    return len(best)


def ensure(dicts_dir=None, db_path=None) -> str:
    """'present' if built, 'built' after building, 'no-source' if dicts/jpdb absent."""
    from ankienrich import paths
    dicts_dir = Path(dicts_dir or paths.DICTS_DIR)
    db_path = db_path or paths.FREQ_DB
    if Path(db_path).exists():
        return "present"
    if not _banks(dicts_dir, DICT_ID):
        return "no-source"
    build(str(dicts_dir), db_path)
    return "built"
```

- [ ] **Step 6: Run it to verify it passes**

Run: `uv run pytest tests/test_freq.py -v`
Expected: still FAIL on the import of `freq_index` (created in Task 2). That is fine — proceed to Task 2, then re-run.

- [ ] **Step 7: Commit**

```bash
git add ankienrich/freq/__init__.py ankienrich/freq/build.py ankienrich/paths.py tests/test_freq.py
git commit -m "feat(freq): build var/freq.sqlite from the JPDB Yomitan dict"
```

---

### Task 2: Frequency index — open + lookup

**Files:**
- Create: `ankienrich/freq/index.py`
- Test: `tests/test_freq.py` (extend)

- [ ] **Step 1: Add a failing lookup test**

Append to `tests/test_freq.py`:

```python
def test_lookup_falls_back_surface_then_reading(tmp_path):
    _make_jpdb(tmp_path)
    db = tmp_path / "freq.sqlite"
    freq_build.build(str(tmp_path), str(db))
    conn = freq_index.open_index(str(db))
    # surface hit
    assert freq_index.lookup(conn, "箸", "はし") == (50, "50")
    # surface miss -> reading hit (kana display strips nothing here)
    assert freq_index.lookup(conn, "未知漢字", "はし") == (9, "9㋕")
    # total miss
    assert freq_index.lookup(conn, "存在しない", "ないよ") is None
    conn.close()
```

- [ ] **Step 2: Run it to verify it fails**

Run: `uv run pytest tests/test_freq.py::test_lookup_falls_back_surface_then_reading -v`
Expected: FAIL — `ModuleNotFoundError: ankienrich.freq.index`.

- [ ] **Step 3: Implement the index**

Create `ankienrich/freq/index.py`:

```python
"""Open + query the frequency index (freq.sqlite). Keyed by headword string;
a (term, reading) lookup tries the surface form first, then the kana reading."""
import sqlite3

from ankienrich import paths


def open_index(path=None) -> sqlite3.Connection:
    conn = sqlite3.connect(path or paths.FREQ_DB)
    conn.row_factory = sqlite3.Row
    return conn


def lookup(conn, term: str, reading: str) -> tuple[int, str] | None:
    """(rank, display) for term (surface preferred) or reading, else None."""
    for key in (term, reading):
        if not key:
            continue
        row = conn.execute(
            "SELECT value, display FROM freq WHERE key=?", (key,)).fetchone()
        if row is not None:
            return row["value"], row["display"]
    return None
```

- [ ] **Step 4: Run the whole freq suite**

Run: `uv run pytest tests/test_freq.py -v`
Expected: PASS (all three tests).

- [ ] **Step 5: Commit**

```bash
git add ankienrich/freq/index.py tests/test_freq.py
git commit -m "feat(freq): open_index + surface->reading lookup"
```

---

### Task 3: Carry accents + frequency on Fields/Candidate; sort readings by rank

**Files:**
- Modify: `ankienrich/config.py`
- Modify: `ankienrich/enrich_one.py`
- Test: `tests/test_mine.py` (extend; candidate sort)

- [ ] **Step 1: Add the config constant**

In `ankienrich/config.py`, near the other field constants (after `IMAGE = "Image"` is fine), add:

```python
FREQUENCY = "Frequency"    # Kinomoto field; filled by the mining flow from JPDB
```

- [ ] **Step 2: Write the failing candidate-sort test**

Add to `tests/test_mine.py`:

```python
def test_candidates_sorted_by_frequency(tmp_path):
    import sqlite3
    from ankienrich import enrich_one
    # tiny defs.sqlite: one term, two readings
    dbf = tmp_path / "defs.sqlite"
    dc = sqlite3.connect(dbf)
    dc.row_factory = sqlite3.Row
    dc.execute("CREATE TABLE defs (term TEXT, reading TEXT, dict_id TEXT, "
               "kind TEXT, sense_ord INTEGER, html TEXT)")
    dc.executemany("INSERT INTO defs VALUES (?,?,?,?,?,?)", [
        ("方", "ほう", "jmdict", "je", 1, "direction"),
        ("方", "かた", "jmdict", "je", 1, "person"),
    ])
    dc.commit()
    # freq.sqlite: かた is rarer (higher rank number) than ほう
    fbf = tmp_path / "freq.sqlite"
    fc = sqlite3.connect(fbf)
    fc.execute("CREATE TABLE freq (key TEXT PRIMARY KEY, value INT, display TEXT)")
    fc.executemany("INSERT INTO freq VALUES (?,?,?)",
                   [("ほう", 100, "100"), ("かた", 5000, "5000")])
    fc.commit()
    from ankienrich.freq import index as freq_index
    cands = enrich_one.candidates(dc, "方", freq_conn=freq_index.open_index(str(fbf)))
    assert [c.reading for c in cands] == ["ほう", "かた"]   # rank ascending
    assert cands[0].freq == 100
```

- [ ] **Step 3: Run it to verify it fails**

Run: `uv run pytest tests/test_mine.py::test_candidates_sorted_by_frequency -v`
Expected: FAIL — `candidates()` takes no `freq_conn` / `Candidate` has no `freq`.

- [ ] **Step 4: Extend Candidate, Fields, candidates(), build_fields()**

In `ankienrich/enrich_one.py`:

Change the `Candidate` dataclass to carry a frequency:

```python
@dataclass(frozen=True)
class Candidate:
    reading: str
    gloss: str  # short plain-text gloss for the picker button/label
    freq: int | None = None   # JPDB rank, or None when unknown
```

Change `candidates()` to look up + sort by rank (rank ascending; unknown last):

```python
def candidates(defs_conn, term: str, *, freq_conn=None) -> list[Candidate]:
    """Distinct readings for `term` across the dict index, each with a 1-line gloss
    and (when `freq_conn` is given) a JPDB rank. Sorted most-frequent first;
    unknown-rank readings sort last. Empty list = unknown word (caller refuses)."""
    jj, je = defs_index.lookup(defs_conn, term)
    by_reading: dict[str, list] = {}
    for r in (*je, *jj):  # prefer JE gloss for the label
        by_reading.setdefault(r["reading"], []).append(r)
    out = []
    for reading, rows in by_reading.items():
        freq = None
        if freq_conn is not None:
            from .freq import index as freq_index
            hit = freq_index.lookup(freq_conn, term, reading)
            freq = hit[0] if hit else None
        out.append(Candidate(reading=reading, gloss=_plain_gloss(rows), freq=freq))
    out.sort(key=lambda c: (c.freq is None, c.freq or 0))
    return out
```

Add the two new fields to `Fields` (defaults at the end so existing positional constructors keep working):

```python
@dataclass(frozen=True)
class Fields:
    term: str
    reading: str
    furigana: str
    pitch_svg: str
    meaning: str
    definition_source: str
    examples: str
    audio_pick: audio_enrich.Pick | None
    audio_clip: bytes | None
    accents: tuple[int, ...] = ()     # downstep positions (0 = heiban)
    freq: int | None = None           # JPDB rank, or None

    @property
    def has_definition(self) -> bool:
        return bool(self.meaning)
```

In `build_fields()`, add a `freq_conn=None` keyword param and populate both new fields. Change the signature line and body. The function currently ends by constructing `Fields(...)`; update it:

```python
def build_fields(term: str, reading: str, *, defs_conn, ex_conn, audio_conn,
                 acc, freq_conn=None) -> Fields:
    """Compute every card field derivable from (term, reading). Pure read."""
    meaning, source, _kind = defs_enrich.definition_for(defs_conn, term, reading)
    examples = ex_enrich.examples_for(ex_conn, term, reading)

    accents = _pitch.accents_for(acc, term, reading)
    pitch = _pitch_svg.render_multi(reading, accents) if accents else ""

    pick = audio_enrich.lookup(audio_conn, term, reading)
    clip = audio_enrich.read_clip(audio_conn, pick) if pick else None
    if clip is None:
        pick = None

    freq = None
    if freq_conn is not None:
        from .freq import index as freq_index
        hit = freq_index.lookup(freq_conn, term, reading)
        freq = hit[0] if hit else None

    return Fields(
        term=term, reading=reading, furigana=term_furigana(term, reading),
        pitch_svg=pitch, meaning=meaning, definition_source=source,
        examples=examples, audio_pick=pick, audio_clip=clip,
        accents=tuple(accents or ()), freq=freq)
```

Note: keep the rest of the existing `build_fields` body (the `return Fields(...)` it already had) — replace the final constructor with the one above so `examples`/`audio_pick`/`audio_clip` keywords match the current code. Verify the existing keyword names against the file before editing.

- [ ] **Step 5: Run the candidate-sort test**

Run: `uv run pytest tests/test_mine.py::test_candidates_sorted_by_frequency -v`
Expected: PASS.

- [ ] **Step 6: Run the full existing mine suite (regression for the dataclass change)**

Run: `uv run pytest tests/test_mine.py -v`
Expected: PASS (positional `Fields(...)` constructors still valid via defaults).

- [ ] **Step 7: Commit**

```bash
git add ankienrich/config.py ankienrich/enrich_one.py tests/test_mine.py
git commit -m "feat(mine): carry accents + JPDB freq on Fields; sort readings by rank"
```

---

### Task 4: HTML→Telegram converter + pitch text helper

**Files:**
- Modify: `ankienrich/mine.py`
- Test: `tests/test_mine.py` (extend)

- [ ] **Step 1: Write failing tests for `tg_html` and `pitch_text`**

Add to `tests/test_mine.py`:

```python
def test_tg_html_blocks_become_lines_and_bold_maps():
    html = ('<div><span style="font-weight:bold">林檎</span></div>'
            '<div>バラ科の落葉高木</div><div>②その果実</div>')
    out = mine.tg_html(html)
    assert out == "<b>林檎</b>\nバラ科の落葉高木\n②その果実"


def test_tg_html_escapes_and_strips_unknown_tags():
    out = mine.tg_html('<span class="x">a &amp; b < c</span>')
    assert out == "a &amp; b &lt; c"   # text escaped, span dropped


def test_tg_html_caps_length_and_stays_balanced():
    long = "<div>" + "あ" * 5000 + "</div>"
    out = mine.tg_html(long, cap=100)
    assert out.endswith("…")
    assert len(out) < 200
    assert out.count("<b>") == out.count("</b>")   # never a dangling tag


def test_pitch_text_single_multiple_and_empty():
    assert mine.pitch_text("りんご", (0,)) == "りんご [0]"
    assert mine.pitch_text("たまご", (0, 2)) == "たまご [0] / [2]"
    assert mine.pitch_text("りんご", ()) == ""
```

- [ ] **Step 2: Run them to verify they fail**

Run: `uv run pytest tests/test_mine.py -k "tg_html or pitch_text" -v`
Expected: FAIL — `mine.tg_html` / `mine.pitch_text` undefined.

- [ ] **Step 3: Implement both helpers in `mine.py`**

Add these imports at the top of `ankienrich/mine.py` (alongside the existing `import re as _re`):

```python
from html import escape as _escape
from html.parser import HTMLParser
```

Add the helpers in the "Pure UI helpers" section (after `_plain`):

```python
# Block-level tags whose boundaries become newlines in the text preview.
_BLOCK = {"div", "p", "br", "li", "tr", "table", "h1", "h2", "h3"}


def _is_bold(attrs) -> bool:
    for name, val in attrs:
        if name == "style" and val and "font-weight" in val and (
                "bold" in val or "700" in val or "600" in val):
            return True
    return False


class _TgHtml(HTMLParser):
    """Convert structured-content dict HTML into Telegram's HTML subset: block
    boundaries -> newlines, bold spans -> <b>, all other tags dropped, text
    escaped. Caps visible length and always emits balanced bold so the result is
    valid Telegram HTML (an unbalanced/half tag makes send_message fail)."""

    def __init__(self, cap: int):
        super().__init__(convert_charrefs=True)
        self.cap = cap
        self.parts: list[str] = []
        self.stack: list[bool] = []   # per open element: did it open bold?
        self.bold = 0                 # current emitted <b> nesting
        self.vis = 0                  # visible chars emitted
        self.done = False

    def _nl(self):
        if self.parts and not self.parts[-1].endswith("\n"):
            self.parts.append("\n")

    def _open_bold(self):
        self.bold += 1
        if self.bold == 1:
            self.parts.append("<b>")

    def _close_bold(self):
        if self.bold > 0:
            self.bold -= 1
            if self.bold == 0:
                self.parts.append("</b>")

    def handle_starttag(self, tag, attrs):
        if self.done:
            return
        if tag in _BLOCK:
            self._nl()
        bold = tag in ("b", "strong") or _is_bold(attrs)
        if bold:
            self._open_bold()
        if tag != "br":                      # void element: no matching endtag
            self.stack.append(bold)

    def handle_startendtag(self, tag, attrs):
        if tag in _BLOCK:
            self._nl()

    def handle_endtag(self, tag):
        if self.done:
            return
        bold = self.stack.pop() if self.stack else False
        if bold:
            self._close_bold()
        if tag in _BLOCK:
            self._nl()

    def handle_data(self, data):
        if self.done:
            return
        room = self.cap - self.vis
        if len(data) > room:
            data, self.done = data[:room], True
        self.vis += len(data)
        self.parts.append(_escape(data, quote=False))
        if self.done:
            while self.bold > 0:
                self._close_bold()
            self.parts.append("…")

    def result(self) -> str:
        while self.bold > 0:
            self._close_bold()
        s = "".join(self.parts)
        s = _re.sub(r"[ \t]*\n[ \t]*", "\n", s)
        s = _re.sub(r"\n{3,}", "\n\n", s)
        return s.strip()


def tg_html(html: str, cap: int = 800) -> str:
    """Dict HTML -> Telegram-safe HTML (see _TgHtml)."""
    p = _TgHtml(cap)
    p.feed(html or "")
    p.close()
    return p.result()


def pitch_text(reading: str, accents) -> str:
    """`reading [n]`, or `reading [a] / [b]` for multiple accepted accents; '' if
    none."""
    if not accents:
        return ""
    return reading + " " + " / ".join(f"[{a}]" for a in accents)
```

- [ ] **Step 4: Run the helper tests**

Run: `uv run pytest tests/test_mine.py -k "tg_html or pitch_text" -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ankienrich/mine.py tests/test_mine.py
git commit -m "feat(mine): tg_html dict->Telegram converter + pitch_text helper"
```

---

### Task 5: Rewrite preview_text; freq on the picker; write the card's Frequency field

**Files:**
- Modify: `ankienrich/mine.py`
- Test: `tests/test_mine.py` (rewrite preview test; extend candidate + create_card tests)

- [ ] **Step 1: Update the preview + candidate-row tests**

Replace `test_preview_text_includes_reading_meaning` in `tests/test_mine.py` with:

```python
def test_preview_text_header_carries_pitch_and_freq():
    from ankienrich.enrich_one import Fields
    f = Fields("林檎", "りんご", "林檎[りんご]", "<svg/>",
               '<div><span style="font-weight:bold">林檎</span></div><div>果実</div>',
               "三省堂", "", None, b"x", accents=(0,), freq=1234)
    txt = mine.preview_text(f)
    assert txt.startswith("📖 林檎（りんご [0]） #1234")
    assert "<b>林檎</b>" in txt and "果実" in txt
    assert "三省堂" in txt
    assert "audio:" not in txt and "pitch:" not in txt


def test_preview_text_without_pitch_or_freq():
    from ankienrich.enrich_one import Fields
    f = Fields("箸", "はし", "箸[はし]", "", "chopsticks", "JMdict", "", None, None)
    txt = mine.preview_text(f)
    assert txt.startswith("📖 箸（はし）")
    assert "#" not in txt
```

And update `test_candidate_rows_label_and_payload` (the `Candidate` import gains an optional `freq`):

```python
def test_candidate_rows_label_and_payload():
    from ankienrich.enrich_one import Candidate
    rows = mine.candidate_rows([Candidate("かど", "corner", 800),
                                Candidate("つの", "horn", None)])
    assert rows[0] == ("かど — corner  #800", "cand:0")
    assert rows[1] == ("つの — horn", "cand:1")
```

- [ ] **Step 2: Add a create_card Frequency test**

Add to `tests/test_mine.py`:

```python
def test_create_card_writes_frequency_when_present(tmp_path):
    from ankienrich import enrich_one, config
    col = _open_test_collection(tmp_path)   # reuse the helper used by sibling tests
    fields = enrich_one.Fields("箸", "はし", "箸[はし]", "", "chopsticks",
                               "JMdict", "", None, None, accents=(), freq=42)
    nid = mine.create_card(col, fields, None, deck="Default", tags=[])
    assert col.get_note(nid)[config.FREQUENCY] == "42"
```

> Before writing this, open `tests/test_mine.py` and copy the exact collection-setup helper the existing `test_create_card_*` tests use (e.g. the fixture that builds a collection with the Kinomoto notetype). Match that helper's name/signature instead of `_open_test_collection` if it differs.

- [ ] **Step 3: Run the updated tests to verify they fail**

Run: `uv run pytest tests/test_mine.py -k "preview_text or candidate_rows or create_card_writes_frequency" -v`
Expected: FAIL — old `preview_text` still emits `audio:`/`pitch:`; `candidate_rows` ignores freq; `create_card` doesn't write Frequency.

- [ ] **Step 4: Rewrite `preview_text`, `candidate_rows`; add the Frequency write**

In `ankienrich/mine.py`, replace `preview_text`:

```python
def preview_text(fields) -> str:
    """Header line carries reading+pitch+freq; body is the formatted definition.
    No audio/pitch yes-no lines: audio is evident from the voice clip already sent,
    and pitch now shows the accent inline."""
    head = pitch_text(fields.reading, fields.accents) or fields.reading
    line = f"📖 {fields.term}（{head}）"
    if fields.freq is not None:
        line += f" #{fields.freq}"
    lines = [line]
    if fields.meaning:
        lines += ["", tg_html(fields.meaning)]
    if fields.definition_source:
        lines.append(f"— {fields.definition_source}")
    return "\n".join(lines)
```

Replace `candidate_rows`:

```python
def candidate_rows(cands) -> list[tuple[str, str]]:
    """(button label, callback_data) per candidate reading; shows JPDB rank when
    known so the most-frequent reading (already sorted first) is obvious."""
    rows = []
    for i, c in enumerate(cands):
        label = f"{c.reading} — {c.gloss}"
        if getattr(c, "freq", None) is not None:
            label += f"  #{c.freq}"
        rows.append((label, f"cand:{i}"))
    return rows
```

In `create_card`, after the `n[config.DEFINITION_SOURCE] = fields.definition_source` line (and before the audio block is fine), add:

```python
    if getattr(fields, "freq", None) is not None:
        n[config.FREQUENCY] = str(fields.freq)
```

- [ ] **Step 5: Run the tests**

Run: `uv run pytest tests/test_mine.py -k "preview_text or candidate_rows or create_card" -v`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add ankienrich/mine.py tests/test_mine.py
git commit -m "feat(mine): pitch+freq preview header, ranked picker, Frequency field"
```

---

### Task 6: Wire the freq index into the bot

**Files:**
- Modify: `ankienrich/bot.py`

- [ ] **Step 1: Open + ensure the freq index in `_indexes()`**

In `ankienrich/bot.py`, add the imports near the existing index imports:

```python
from .freq import build as freq_build, index as freq_index
```

Replace `_indexes()` so it ensures + opens freq and returns it as the final tuple element:

```python
def _indexes():
    """(defs_conn, ex_conn, audio_conn, acc, generate, freq_conn), opened once
    (read-only). freq_conn is None when no JPDB source was available to build it."""
    if not hasattr(_indexes, "_cache"):
        freq_conn = None
        if freq_build.ensure() != "no-source":
            freq_conn = freq_index.open_index()
        _indexes._cache = (defs_index.open_index(), ex_index.open_index(),
                           audio_index.open_index(), AccentIndex.load(),
                           make_generator(), freq_conn)
    return _indexes._cache
```

- [ ] **Step 2: Pass freq_conn where Fields/candidates are built**

In `_enter_preview`, change the unpack + `build_fields` call:

```python
        defs_conn, ex_conn, audio_conn, acc, _gen, freq_conn = _indexes()
        s.fields = enrich_one.build_fields(
            s.term, reading, defs_conn=defs_conn, ex_conn=ex_conn,
            audio_conn=audio_conn, acc=acc, freq_conn=freq_conn)
```

In `_word`, change the unpack and the `candidates` call:

```python
        defs_conn, ex_conn, audio_conn, acc, gen, freq_conn = _indexes()
        cands = enrich_one.candidates(defs_conn, term, freq_conn=freq_conn)
```

Update the other two `_indexes()` unpack sites so the trailing `freq_conn` doesn't shift `acc`/`gen`:
- In the `go:sentence` branch: `_, ex_conn, *_ = _indexes()` already takes only [0]/[1] — leave it.
- In the `sent:` branch, change `*_, acc, gen = _indexes()` to:

```python
            _d, _e, _a, acc, gen, _f = _indexes()
```

- [ ] **Step 3: Verify nothing else unpacks `_indexes()`**

Run: `rg -n "_indexes\(\)" ankienrich/bot.py`
Expected: only the four sites above. Confirm each either uses keyword fields or the explicit 6-tuple unpack — no remaining `*_, acc, gen` form.

- [ ] **Step 4: Run the full suite**

Run: `uv run pytest -q`
Expected: PASS (all tests).

- [ ] **Step 5: Smoke-check the preview render offline**

Run:
```bash
uv run python -c "
from ankienrich.enrich_one import Fields
from ankienrich import mine
f = Fields('林檎','りんご','林檎[りんご]','<svg/>',
           '<div><span style=\"font-weight:bold\">林檎</span></div><div>バラ科の落葉高木。</div>',
           '三省堂', '', None, b'x', accents=(0,), freq=1234)
print(mine.preview_text(f))
"
```
Expected output:
```
📖 林檎（りんご [0]） #1234

<b>林檎</b>
バラ科の落葉高木。
— 三省堂
```

- [ ] **Step 6: Commit**

```bash
git add ankienrich/bot.py
git commit -m "feat(bot): open the JPDB freq index; rank readings, show freq in preview"
```

---

### Task 7: Update CLAUDE.md mining note + finish

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Document the freq build input**

In `CLAUDE.md`, in the mining/bot section, add one sentence near the other "first run builds var/*.sqlite" notes:

> Word frequency comes from a JPDB Yomitan frequency dict extracted to `dicts/jpdb/`; first run builds `var/freq.sqlite` (skipped if absent — preview/picker just omit the rank). The mining preview shows the formatted definition, pitch + frequency in the header, and fills the card's `Frequency` field.

- [ ] **Step 2: Run the full suite once more**

Run: `uv run pytest -q`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: note the JPDB freq build input + richer mining preview"
```

- [ ] **Step 4: Delete this plan (throwaway per project workflow)**

```bash
git rm docs/superpowers/plans/2026-06-17-readable-card-preview.md
git commit -m "chore: drop completed readable-preview plan"
```

---

## Self-Review

- **Spec coverage:** formatted definition (Task 4–5 `tg_html`/`preview_text`); pitch in header (Task 4 `pitch_text` + Task 5); frequency shown as `#N` (Task 5); reading picker sorted by frequency (Task 3); card Frequency field filled (Task 3 config + Task 5 write); freq source as a skip-if-absent index (Tasks 1–2, 6). All covered.
- **Placeholder scan:** none — every code step is concrete. Two steps ask the implementer to match an *existing* test helper name (`build_fields` keyword names; the create_card collection fixture) rather than guess; these are verification notes, not placeholders.
- **Type consistency:** `Candidate.freq: int | None`, `Fields.accents: tuple[int,...]`, `Fields.freq: int | None`, `freq_index.lookup -> (int, str) | None`, `_indexes()` 6-tuple ending in `freq_conn`, `tg_html(html, cap=800) -> str`, `pitch_text(reading, accents) -> str` — used consistently across tasks.
- **Order safety:** new `Fields`/`Candidate` fields have defaults at the end, so the many positional constructors in `tests/test_mine.py` stay valid (Task 3 Step 6 regression-checks this).
```
