# Mine: word → card via Telegram — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the Telegram bot turn a single Japanese word into a fully-enriched `jidoujisho Kinomoto` card (dict/pitch/audio + a chosen Massif sentence) and sync it to AnkiWeb.

**Architecture:** A shared single-note path (`enrich_one.build_fields`) reuses each enrichment pass's per-`(Term,Reading)` lookup to produce a card's fields — used for both the in-chat preview and the committed note. `mine.py` adds card creation + a pure conversation state machine; `bot.py` stays thin glue. Massif is the `Sentence` source over `requests`, with the local `examples.sqlite` as fallback. The `Image` field is filled via a web-image pick that reuses the merged `ankienrich/images.py`.

> **IMAGE INTEGRATION (added 2026-06-17, after Task 6):** the image step — originally Phase 2 — is now IN SCOPE because `ankienrich/images.py` was merged from `main`. Tasks 7–11 are amended by the **"Image integration addendum"** at the end of this document. Apply each task's base spec AND its addendum delta together. Tasks 1–6 are unaffected (already complete).

**Tech Stack:** Python ≥3.11, `anki` lib, `requests` (promoted to a direct dep), `pyTelegramBotAPI`, `sudachipy` (via the existing JRP generator), `pytest`.

**Spec:** `docs/superpowers/specs/2026-06-17-mine-word-to-card-design.md`

---

## File map

- **Create** `ankienrich/massif.py` — `top_sentences(term, n, timeout)` scraper.
- **Create** `ankienrich/enrich_one.py` — `Candidate`, `Fields`, `SentenceFields`, `candidates`, `term_furigana`, `build_fields`, `fields_for_sentence`.
- **Create** `ankienrich/mine.py` — `target_deck`, `existing_note`, `create_card`, `commit`, `sentence_options`, plus pure UI builders (`preview_text`, `confirm_text`, `candidate_rows`, `sentence_rows`) and a `Session` dataclass.
- **Modify** `ankienrich/config.py` — `MINED_TAG`, `MINE_SENTENCE_N`, `MASSIF_TIMEOUT`, `MASSIF_URL`, optional `MINE_DECK`.
- **Modify** `ankienrich/notify.py` — migrate `urllib.request` → `requests`.
- **Modify** `ankienrich/audio/enrich.py` — rename `_media_name` → `media_name` (public).
- **Modify** `ankienrich/defs/enrich.py` — add public `definition_for(conn, term, reading)`.
- **Modify** `ankienrich/examples/enrich.py` — add public `examples_for(conn, term, reading)`.
- **Modify** `ankienrich/bot.py` — text/callback handlers, in-memory sessions, voice send.
- **Modify** `pyproject.toml` — add `requests>=2` to `[project.dependencies]`.
- **Create** tests: `tests/test_massif.py`, `tests/test_enrich_one.py`, `tests/test_mine.py`; extend `tests/test_notify.py`.

### Shared test helper (used by several tasks)

`tests/test_enrich_one.py` and `tests/test_mine.py` build a Kinomoto collection with the same field list the real notetype uses. Copy this list verbatim (it matches the live notetype):

```python
KINOMOTO_FIELDS = [
    "Term", "Reading", "Furigana", "Sentence", "SentenceFurigana",
    "Cloze Before", "Cloze Inside", "Cloze After", "Meaning",
    "Expanded Meaning", "Collapsed Meaning", "Notes", "Context", "Frequency",
    "Pitch Accent", "Image", "Term Audio", "Sentence Audio",
    "Original Meaning", "Definition Source", "Examples",
]

def _new_kinomoto(col):
    from ankienrich import config
    m = col.models.new(config.NOTETYPE)
    for fname in KINOMOTO_FIELDS:
        col.models.add_field(m, col.models.new_field(fname))
    m["tmpls"].append(col.models.new_template("c"))
    col.models.add(m)
    return col.models.by_name(config.NOTETYPE)
```

---

## Task 1: Promote `requests`; migrate `notify.py`

**Files:**
- Modify: `pyproject.toml:5-8`
- Modify: `ankienrich/notify.py:1-46`
- Test: `tests/test_notify.py`

- [ ] **Step 1: Add the dependency**

In `pyproject.toml`, change the `dependencies` array to:

```toml
dependencies = [
    "jaconv>=0.3",      # katakana->hiragana (kana.hira, audio reading match)
    "tinycss2>=1.2",    # robust dict-styles.css scoping in defs/build.py
    "requests>=2",      # single HTTP client (massif scraper + telegram notify)
]
```

Run: `uv sync`
Expected: resolves with `requests` now a direct dep (already present transitively).

- [ ] **Step 2: Write the failing test**

Read `tests/test_notify.py` first to match its existing style. Add:

```python
def test_send_message_posts_via_requests(monkeypatch):
    import ankienrich.notify as notify
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "T")
    monkeypatch.setenv("TELEGRAM_CHAT_ID", "42")
    calls = {}
    class _Resp:
        status_code = 200
        def raise_for_status(self): pass
    def _post(url, **kw):
        calls["url"] = url
        calls["json"] = kw.get("json")
        calls["timeout"] = kw.get("timeout")
        return _Resp()
    monkeypatch.setattr(notify.requests, "post", _post)
    notify.send_message("hi")
    assert calls["url"].endswith("/botT/sendMessage")
    assert calls["json"]["chat_id"] == "42"
    assert calls["json"]["text"] == "hi"
    assert calls["timeout"] is not None
```

(If `_credentials()` reads different env var names, adjust the `setenv` names to match the existing `notify.py`.)

- [ ] **Step 3: Run it, expect failure**

Run: `uv run pytest tests/test_notify.py::test_send_message_posts_via_requests -v`
Expected: FAIL (`notify` has no attribute `requests`, or still uses urllib).

- [ ] **Step 4: Migrate `notify.py`**

Replace the `urllib.request`/`urllib.error` imports and the body of `send_message` with `requests`. Preserve: the same Telegram endpoint, the `chat_id`/`text` payload, the existing parse-mode/keyboard params if any, and the existing error behaviour (a send failure must raise the same way the callers expect — check `cli._safe_notify`, which catches broad `Exception`). Keep `_credentials`, `_detail`, `_line`, `_skipped`, `format_report` unchanged. Skeleton:

```python
import requests
from . import config  # keep existing imports the file already needs

def send_message(text: str) -> None:
    token, chat_id = _credentials()
    resp = requests.post(
        f"https://api.telegram.org/bot{token}/sendMessage",
        json={"chat_id": chat_id, "text": text},
        timeout=10,
    )
    resp.raise_for_status()
```

Match the exact payload keys the current implementation sends (read it before editing — it may set `parse_mode` or `disable_web_page_preview`; carry those over).

- [ ] **Step 5: Run the test + full notify suite**

Run: `uv run pytest tests/test_notify.py -v`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pyproject.toml uv.lock ankienrich/notify.py tests/test_notify.py
git commit -m "deps: promote requests; migrate notify off urllib"
```

---

## Task 2: `massif.py` sentence scraper

**Files:**
- Modify: `ankienrich/config.py`
- Create: `ankienrich/massif.py`
- Test: `tests/test_massif.py`

Massif markup (verified live): each result is `<li class="text-japanese">` whose **first** `<div>` holds the sentence, with the query term wrapped in `<em>…</em>`.

- [ ] **Step 1: Add config**

Append to `ankienrich/config.py`:

```python
# Single-word mining (Telegram bot).
MINED_TAG = "ankienrich::mined"
MINE_SENTENCE_N = 5
MASSIF_URL = "https://massif.la/ja/search"
MASSIF_TIMEOUT = 5  # seconds
MINE_DECK = ""      # "" = derive the modal deck of existing NOTETYPE cards
```

- [ ] **Step 2: Write the failing test**

```python
from ankienrich import massif

_FIXTURE = '''
<div id="results-list"><ul>
<li class="text-japanese">
  <div>いろいろと<em>勉強</em>になりました。</div>
  <div class="result-meta"><a class="source_link" href="x">src</a></div>
</li>
<li class="text-japanese">
  <div><em>勉強</em>させて&#12356;ただき、ありがとうございました</div>
  <div class="result-meta"><a class="source_link" href="y">src</a></div>
</li>
<li class="text-japanese">
  <div>とっても<em>勉強</em>になりました。</div>
</li>
</ul></div>
'''

def test_parse_extracts_sentences_strips_em_and_unescapes():
    out = massif.parse(_FIXTURE, n=2)
    assert out == ["いろいろと勉強になりました。",
                   "勉強させていただき、ありがとうございました"]

def test_parse_caps_at_n():
    assert len(massif.parse(_FIXTURE, n=1)) == 1

def test_top_sentences_returns_empty_on_network_error(monkeypatch):
    def _boom(*a, **k):
        raise massif.requests.RequestException("down")
    monkeypatch.setattr(massif.requests, "get", _boom)
    assert massif.top_sentences("勉強", n=5, timeout=1) == []
```

- [ ] **Step 3: Run, expect failure**

Run: `uv run pytest tests/test_massif.py -v`
Expected: FAIL (no module `ankienrich.massif`).

- [ ] **Step 4: Implement `ankienrich/massif.py`**

```python
"""massif.la sentence search — the Sentence source for single-word mining.

No public API exists; this scrapes the result list. Each result is
`<li class="text-japanese">` whose first <div> is the sentence (query term
wrapped in <em>). Network/parse failures return [] so callers can fall back.
"""
from __future__ import annotations

import html
import re

import requests

from . import config

_LI = re.compile(r'<li class="text-japanese">\s*<div>(.*?)</div>', re.S)
_TAG = re.compile(r"<[^>]+>")
_HEADERS = {"User-Agent": "Mozilla/5.0 (anki-enrich)"}


def parse(body: str, n: int) -> list[str]:
    out = []
    for frag in _LI.findall(body):
        text = html.unescape(_TAG.sub("", frag)).strip()
        if text:
            out.append(text)
        if len(out) >= n:
            break
    return out


def top_sentences(term: str, n: int = config.MINE_SENTENCE_N,
                  timeout: int = config.MASSIF_TIMEOUT) -> list[str]:
    """Top-n Massif example sentences for `term`, or [] on any failure."""
    try:
        resp = requests.get(config.MASSIF_URL, params={"q": term},
                            headers=_HEADERS, timeout=timeout)
        resp.raise_for_status()
        return parse(resp.text, n)
    except requests.RequestException:
        return []
```

- [ ] **Step 5: Run, expect pass**

Run: `uv run pytest tests/test_massif.py -v`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add ankienrich/config.py ankienrich/massif.py tests/test_massif.py
git commit -m "feat(massif): scrape top example sentences for a term"
```

---

## Task 3: Public per-note enrich wrappers

Both passes already have a pure `_compute`; expose stable public entry points so `enrich_one` doesn't reach into privates. Also make `audio` media-name public.

**Files:**
- Modify: `ankienrich/defs/enrich.py`
- Modify: `ankienrich/examples/enrich.py`
- Modify: `ankienrich/audio/enrich.py:_media_name` (rename to `media_name`, update internal callers)
- Test: `tests/test_enrich.py`, `tests/test_examples_index.py`, `tests/test_audio.py`

- [ ] **Step 1: Write failing tests**

In `tests/test_enrich.py` (defs) add — reuse the file's existing in-memory defs db helper if present, else build one with columns `term,reading,dict_id,kind,sense_ord,html`:

```python
def test_definition_for_returns_meaning_source_kind(tmp_path):
    from ankienrich.defs import index, enrich
    import sqlite3
    db = str(tmp_path / "defs.sqlite")
    conn = sqlite3.connect(db); conn.row_factory = sqlite3.Row
    conn.execute("CREATE TABLE defs (term TEXT, reading TEXT, dict_id TEXT, "
                 "kind TEXT, sense_ord INTEGER, html TEXT)")
    conn.execute("INSERT INTO defs VALUES ('愛','あい','jmdict','je',0,'love')")
    conn.commit()
    meaning, source, kind = enrich.definition_for(conn, "愛", "あい")
    assert "love" in meaning
    assert kind == "english"
```

In `tests/test_examples_index.py` add (reuses its `_make_db`/`_row`):

```python
def test_examples_for_returns_block(tmp_path):
    from ankienrich.examples import index, enrich
    db = str(tmp_path / "examples.sqlite")
    _make_db(db, [_row("愛", "あい", "tatoeba", 0, "愛は強い")])
    conn = index.open_index(db)
    html = enrich.examples_for(conn, "愛", "あい")
    assert "愛は強い" in html
```

In `tests/test_audio.py` add:

```python
def test_media_name_is_public():
    from ankienrich.audio import enrich
    pick = enrich.Pick("nhk16", "123.opus")
    assert enrich.media_name(pick) == "la_nhk16_123.opus"
```

- [ ] **Step 2: Run, expect failure**

Run: `uv run pytest tests/test_enrich.py -k definition_for tests/test_examples_index.py -k examples_for tests/test_audio.py -k media_name -v`
Expected: FAIL (attributes don't exist).

- [ ] **Step 3: Add the wrappers**

In `ankienrich/defs/enrich.py`, after `_compute`:

```python
def definition_for(conn, term, reading):
    """Public single-note definition lookup: (meaning, source, kind)."""
    return _compute(conn, term, reading)
```

In `ankienrich/examples/enrich.py`, after `_compute`:

```python
def examples_for(conn, term, reading) -> str:
    """Public single-note examples block (HTML), '' when none."""
    return _compute(conn, term, reading)
```

In `ankienrich/audio/enrich.py`, rename `def _media_name(pick)` to `def media_name(pick)` and update the two callers (`run_backfill`, `run_repitch`) from `_media_name(pick)` to `media_name(pick)`.

- [ ] **Step 4: Run, expect pass**

Run: `uv run pytest tests/test_enrich.py tests/test_examples_index.py tests/test_audio.py -v`
Expected: PASS (whole files still green — the rename didn't break callers).

- [ ] **Step 5: Commit**

```bash
git add ankienrich/defs/enrich.py ankienrich/examples/enrich.py ankienrich/audio/enrich.py tests/
git commit -m "refactor: public single-note enrich entry points (definition_for/examples_for/media_name)"
```

---

## Task 4: `enrich_one` — candidates + term furigana

**Files:**
- Create: `ankienrich/enrich_one.py`
- Test: `tests/test_enrich_one.py`

- [ ] **Step 1: Write failing tests**

```python
import sqlite3
import pytest
from ankienrich import enrich_one


def _defs_db(tmp_path, rows):
    db = str(tmp_path / "defs.sqlite")
    conn = sqlite3.connect(db); conn.row_factory = sqlite3.Row
    conn.execute("CREATE TABLE defs (term TEXT, reading TEXT, dict_id TEXT, "
                 "kind TEXT, sense_ord INTEGER, html TEXT)")
    conn.executemany("INSERT INTO defs VALUES (?,?,?,?,?,?)", rows)
    conn.commit()
    return conn


def test_term_furigana_kanji():
    assert enrich_one.term_furigana("部活", "ぶかつ") == "部活[ぶかつ]"

def test_term_furigana_kana_is_plain():
    assert enrich_one.term_furigana("みきき", "みきき") == "みきき"

def test_candidates_groups_by_reading_with_gloss(tmp_path):
    conn = _defs_db(tmp_path, [
        ("髪", "かみ", "jmdict", "je", 0, "hair"),
        ("神", "かみ", "jmdict", "je", 0, "god"),  # same surface? no -> separate term
    ])
    # query a single surface that has one reading
    conn.execute("INSERT INTO defs VALUES ('箸','はし','jmdict','je',0,'chopsticks')")
    conn.commit()
    cands = enrich_one.candidates(conn, "箸")
    assert [c.reading for c in cands] == ["はし"]
    assert "chopsticks" in cands[0].gloss

def test_candidates_multiple_readings(tmp_path):
    conn = _defs_db(tmp_path, [
        ("角", "かど", "jmdict", "je", 0, "corner"),
        ("角", "つの", "jmdict", "je", 0, "horn"),
    ])
    cands = enrich_one.candidates(conn, "角")
    assert {c.reading for c in cands} == {"かど", "つの"}

def test_candidates_empty_when_no_hits(tmp_path):
    conn = _defs_db(tmp_path, [])
    assert enrich_one.candidates(conn, "存在しない") == []
```

- [ ] **Step 2: Run, expect failure**

Run: `uv run pytest tests/test_enrich_one.py -v`
Expected: FAIL (no module).

- [ ] **Step 3: Implement the candidates + furigana part of `enrich_one.py`**

```python
"""Single-note enrichment: build the field set for one (term, reading) by reusing
each pass's per-note lookup. Used by the Telegram mining flow for BOTH the chat
preview and the committed card, so preview == written value. Pure reads (no col
mutation): audio bytes are returned, not written; the writer (mine.create_card)
persists them.
"""
from __future__ import annotations

import html as _html
import re
from dataclasses import dataclass

from . import config
from .defs import index as defs_index, enrich as defs_enrich
from .examples import enrich as ex_enrich
from .audio import enrich as audio_enrich
from .jrp_fill.align import furigana_segments

_TAG = re.compile(r"<[^>]+>")


@dataclass(frozen=True)
class Candidate:
    reading: str
    gloss: str            # short plain-text gloss for the picker button/label


def term_furigana(term: str, reading: str) -> str:
    """Anki furigana notation for a headword, e.g. ('部活','ぶかつ') -> '部活[ぶかつ]';
    kana-only terms render plain."""
    segs = furigana_segments(term, reading)
    return "".join(f"{b}[{r}]" if r else b for b, r in segs)


def _plain_gloss(rows) -> str:
    """First sense's text, tags stripped, clipped — for the disambiguation label."""
    if not rows:
        return ""
    text = _html.unescape(_TAG.sub("", rows[0]["html"])).strip()
    return text[:40]


def candidates(defs_conn, term: str) -> list[Candidate]:
    """Distinct readings for `term` across the dict index, each with a 1-line gloss.
    Empty list = unknown word (caller refuses)."""
    jj, je = defs_index.lookup(defs_conn, term)
    by_reading: dict[str, list] = {}
    for r in (*je, *jj):                     # prefer JE gloss for the label
        by_reading.setdefault(r["reading"], []).append(r)
    out = []
    for reading, rows in by_reading.items():
        out.append(Candidate(reading=reading, gloss=_plain_gloss(rows)))
    return out
```

- [ ] **Step 4: Run, expect pass**

Run: `uv run pytest tests/test_enrich_one.py -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ankienrich/enrich_one.py tests/test_enrich_one.py
git commit -m "feat(enrich_one): term furigana + reading-grouped candidates"
```

---

## Task 5: `enrich_one.build_fields`

**Files:**
- Modify: `ankienrich/enrich_one.py`
- Test: `tests/test_enrich_one.py`

- [ ] **Step 1: Write failing test**

`build_fields` injects its indexes + accent index so tests need no real data files. Use a fake accent index and fake audio/examples connections.

```python
def test_build_fields_assembles_everything(tmp_path, monkeypatch):
    conn = _defs_db(tmp_path, [("箸", "はし", "jmdict", "je", 0, "chopsticks")])

    class _Acc:  # stand-in AccentIndex
        def lookup(self, lemma, reading):
            return [0] if reading == "はし" else None

    # fake audio: lookup returns a Pick, read_clip returns bytes
    from ankienrich.audio.enrich import Pick
    monkeypatch.setattr(enrich_one.audio_enrich, "lookup",
                        lambda c, t, r: Pick("nhk16", "1.opus"))
    monkeypatch.setattr(enrich_one.audio_enrich, "read_clip",
                        lambda c, p: b"OPUSDATA")
    monkeypatch.setattr(enrich_one.ex_enrich, "examples_for",
                        lambda c, t, r: "<div>例</div>")

    f = enrich_one.build_fields(
        "箸", "はし",
        defs_conn=conn, ex_conn=object(), audio_conn=object(), acc=_Acc())

    assert f.term == "箸" and f.reading == "はし"
    assert f.furigana == "箸[はし]"
    assert f.pitch_svg.startswith("<svg")
    assert "chopsticks" in f.meaning
    assert f.definition_source  # non-empty label
    assert f.examples == "<div>例</div>"
    assert f.audio_clip == b"OPUSDATA"
    assert f.audio_pick.source == "nhk16"
    assert f.has_definition is True

def test_build_fields_no_audio(tmp_path, monkeypatch):
    conn = _defs_db(tmp_path, [("箸", "はし", "jmdict", "je", 0, "chopsticks")])
    class _Acc:
        def lookup(self, l, r): return None
    monkeypatch.setattr(enrich_one.audio_enrich, "lookup", lambda c, t, r: None)
    monkeypatch.setattr(enrich_one.ex_enrich, "examples_for", lambda c, t, r: "")
    f = enrich_one.build_fields("箸", "はし", defs_conn=conn,
                                ex_conn=object(), audio_conn=object(), acc=_Acc())
    assert f.audio_clip is None and f.audio_pick is None
    assert f.pitch_svg == ""        # accent not found -> no graph
```

- [ ] **Step 2: Run, expect failure**

Run: `uv run pytest tests/test_enrich_one.py -k build_fields -v`
Expected: FAIL (`build_fields` / `Fields` missing).

- [ ] **Step 3: Implement**

Add to `ankienrich/enrich_one.py`:

```python
from . import pitch as _pitch, pitch_svg as _pitch_svg


@dataclass
class Fields:
    term: str
    reading: str
    furigana: str
    pitch_svg: str
    meaning: str
    definition_source: str
    examples: str
    audio_pick: object | None
    audio_clip: bytes | None

    @property
    def has_definition(self) -> bool:
        return bool(self.meaning)


def build_fields(term: str, reading: str, *, defs_conn, ex_conn, audio_conn,
                 acc) -> Fields:
    """Compute every card field derivable from (term, reading). Pure read."""
    meaning, source, _kind = defs_enrich.definition_for(defs_conn, term, reading)
    examples = ex_enrich.examples_for(ex_conn, term, reading)

    accents = _pitch.accents_for(acc, term, reading)
    pitch = _pitch_svg.render_multi(reading, accents) if accents else ""

    pick = audio_enrich.lookup(audio_conn, term, reading)
    clip = audio_enrich.read_clip(audio_conn, pick) if pick else None
    if clip is None:
        pick = None

    return Fields(
        term=term, reading=reading, furigana=term_furigana(term, reading),
        pitch_svg=pitch, meaning=meaning, definition_source=source,
        examples=examples, audio_pick=pick, audio_clip=clip)
```

Note: `_pitch.accents_for` accepts any object with `.lookup(lemma, reading)`, so the fake `_Acc` works. Verify `pitch_svg.render_multi(reading, accents)` is the real signature (it is — see `pitch._iter_targets`).

- [ ] **Step 4: Run, expect pass**

Run: `uv run pytest tests/test_enrich_one.py -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ankienrich/enrich_one.py tests/test_enrich_one.py
git commit -m "feat(enrich_one): build_fields assembles a card from (term,reading)"
```

---

## Task 6: `enrich_one.fields_for_sentence`

Turns a plain sentence into the markup `Sentence`, the Anki `SentenceFurigana`, and the cloze split.

**Files:**
- Modify: `ankienrich/enrich_one.py`
- Test: `tests/test_enrich_one.py`

- [ ] **Step 1: Write failing test**

The JRP generator is injected (so tests need no Sudachi). A fake `generate` returns markup; the cloze split is computed by the real `cloze.locate` on the stripped surface.

```python
def test_fields_for_sentence_builds_markup_furigana_cloze():
    # fake generator: wrap the term in JRP unit markup, leave rest plain
    def generate(plain):
        return plain.replace("勉強", "{[勉強|べんきょう];0}")
    sf = enrich_one.fields_for_sentence(
        "勉強", "べんきょう", "毎日勉強する", generate=generate, acc=None)
    assert sf.sentence == "毎日{[勉強|べんきょう];0}する"
    assert sf.sentence_furigana == "毎日 勉強[べんきょう]する"   # markup.jrp_to_furigana
    assert sf.cloze_before == "毎日"
    assert sf.cloze_inside == "勉強"
    assert sf.cloze_after == "する"

def test_fields_for_sentence_unlocatable_leaves_cloze_empty():
    def generate(plain):
        return plain  # no markup
    sf = enrich_one.fields_for_sentence(
        "存在", "そんざい", "毎日勉強する", generate=generate, acc=None)
    assert sf.sentence == "毎日勉強する"
    assert (sf.cloze_before, sf.cloze_inside, sf.cloze_after) == ("", "", "")
```

(`markup.jrp_to_furigana` inserts a leading space before a kanji ruby run — `毎日 勉強[べんきょう]する` — matching real `SentenceFurigana` values like `掻[か]き 回[まわ]す`. If the exact spacing differs when you run it, set the expected string to the actual `markup.jrp_to_furigana(sf.sentence)` output — that function is the source of truth, already tested in `tests/test_markup.py`.)

- [ ] **Step 2: Run, expect failure**

Run: `uv run pytest tests/test_enrich_one.py -k fields_for_sentence -v`
Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `ankienrich/enrich_one.py`:

```python
from . import markup as _markup, cloze as _cloze


@dataclass
class SentenceFields:
    sentence: str            # JRP markup
    sentence_furigana: str   # Anki furigana notation
    cloze_before: str
    cloze_inside: str
    cloze_after: str


def fields_for_sentence(term: str, reading: str, plain_sentence: str, *,
                        generate, acc) -> SentenceFields:
    """Markup + furigana + cloze for a chosen plain sentence. `generate` is the
    JRP generator (jrp_fill.make_generator()); `acc` an AccentIndex for cloze
    tokenisation (may be None for the verbatim-find fast path)."""
    sentence = generate(plain_sentence)
    furi = _markup.jrp_to_furigana(sentence)
    plain = _cloze.strip_markup(sentence).strip()
    split = _cloze.locate(term, reading, plain, acc)
    before, inside, after = split if split else ("", "", "")
    return SentenceFields(sentence=sentence, sentence_furigana=furi,
                          cloze_before=before, cloze_inside=inside,
                          cloze_after=after)
```

- [ ] **Step 4: Run, expect pass**

Run: `uv run pytest tests/test_enrich_one.py -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ankienrich/enrich_one.py tests/test_enrich_one.py
git commit -m "feat(enrich_one): fields_for_sentence (markup + furigana + cloze)"
```

---

## Task 7: `mine.py` — deck resolution, dup check, card creation

**Files:**
- Create: `ankienrich/mine.py`
- Test: `tests/test_mine.py`

- [ ] **Step 1: Write failing tests**

```python
import pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankienrich import config, mine, enrich_one
from ankienrich.audio.enrich import Pick

# paste KINOMOTO_FIELDS + _new_kinomoto from the shared helper block above


def _col(tmp_path):
    c = Collection(str(tmp_path / "k.anki2"))
    _new_kinomoto(c)
    return c

def _add(col, term, reading, deck):
    m = col.models.by_name(config.NOTETYPE)
    n = col.new_note(m); n["Term"] = term; n["Reading"] = reading
    col.add_note(n, col.decks.id(deck))
    return n

def test_target_deck_is_modal_deck(tmp_path):
    col = _col(tmp_path)
    _add(col, "A", "a", "言葉文章")
    _add(col, "B", "b", "言葉文章")
    _add(col, "C", "c", "Other")
    assert mine.target_deck(col) == "言葉文章"
    col.close()

def test_existing_note_matches_term_and_reading(tmp_path):
    col = _col(tmp_path)
    _add(col, "箸", "はし", "言葉文章")
    assert mine.existing_note(col, "箸", "はし") is not None
    assert mine.existing_note(col, "箸", "ちょう") is None
    assert mine.existing_note(col, "未知", "みち") is None
    col.close()

def test_create_card_writes_fields_media_deck_tags(tmp_path):
    col = _col(tmp_path)
    fields = enrich_one.Fields(
        term="箸", reading="はし", furigana="箸[はし]", pitch_svg="<svg/>",
        meaning="chopsticks", definition_source="JMdict", examples="<div>例</div>",
        audio_pick=Pick("nhk16", "1.opus"), audio_clip=b"OPUS")
    sf = enrich_one.SentenceFields("毎日{箸}", "毎日 箸[はし]", "毎日", "箸", "を使う")
    nid = mine.create_card(col, fields, sf, deck="言葉文章",
                           tags=[config.MINED_TAG, config.ENRICHED_TAG])
    n = col.get_note(nid)
    assert n["Term"] == "箸" and n["Furigana"] == "箸[はし]"
    assert n["Meaning"] == "chopsticks" and n["Definition Source"] == "JMdict"
    assert n["Examples"] == "<div>例</div>"
    assert n["Pitch Accent"] == "<svg/>"
    assert n["Sentence"] == "毎日{箸}" and n["Cloze Inside"] == "箸"
    assert n["Term Audio"].startswith("[sound:") and n["Term Audio"].endswith("]")
    assert config.MINED_TAG in n.tags and config.ENRICHED_TAG in n.tags
    # lands in the requested deck
    cid = col.card_ids(nid)[0]
    assert col.decks.name(col.get_card(cid).did) == "言葉文章"
    col.close()

def test_create_card_without_sentence_leaves_those_fields_empty(tmp_path):
    col = _col(tmp_path)
    fields = enrich_one.Fields("箸", "はし", "箸[はし]", "", "chopsticks", "JMdict",
                               "", None, None)
    nid = mine.create_card(col, fields, None, deck="言葉文章",
                           tags=[config.MINED_TAG])
    n = col.get_note(nid)
    assert n["Sentence"] == "" and n["Cloze Inside"] == "" and n["Term Audio"] == ""
    col.close()
```

- [ ] **Step 2: Run, expect failure**

Run: `uv run pytest tests/test_mine.py -v`
Expected: FAIL (no module `ankienrich.mine`).

- [ ] **Step 3: Implement the collection-writing part of `mine.py`**

```python
"""Single-word mining: create a fully-enriched Kinomoto card from one word and
sync it. The conversation flow lives here as pure helpers + a Session dataclass;
bot.py is the thin Telegram glue."""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field

from . import collection, config, runlock, sync
from .audio import enrich as audio_enrich


def target_deck(col) -> str:
    """Deck for new cards: the configured override, else the modal deck of
    existing NOTETYPE cards, else the current deck."""
    if config.MINE_DECK:
        return config.MINE_DECK
    counts = Counter()
    for nid in col.find_notes(collection.notetype_search()):
        for cid in col.card_ids(nid):
            counts[col.get_card(cid).did] += 1
    if not counts:
        return col.decks.current()["name"]
    return col.decks.name(counts.most_common(1)[0][0])


def existing_note(col, term: str, reading: str):
    """nid of an existing Kinomoto note with this exact term+reading, or None."""
    q = f'note:"{config.NOTETYPE}" "{config.TERM}:{term}"'
    for nid in col.find_notes(q):
        n = col.get_note(nid)
        if n[config.TERM].strip() == term and n[config.READING].strip() == reading:
            return nid
    return None


def create_card(col, fields, sentence_fields, *, deck: str, tags) -> int:
    """Build + add the note. Caller holds the runlock and has backed up."""
    m = col.models.by_name(config.NOTETYPE)
    if m is None:
        raise RuntimeError("target notetype missing: " + config.NOTETYPE)
    n = col.new_note(m)
    n[config.TERM] = fields.term
    n[config.READING] = fields.reading
    n[config.TERM_FURIGANA] = fields.furigana
    n[config.PITCH] = fields.pitch_svg
    n[config.DEFINITION] = fields.meaning
    n[config.DEFINITION_SOURCE] = fields.definition_source
    n[config.EXAMPLES] = fields.examples
    if fields.audio_clip and fields.audio_pick:
        fname = col.media.write_data(
            audio_enrich.media_name(fields.audio_pick), fields.audio_clip)
        n[config.TERM_AUDIO] = f"[sound:{fname}]"
    if sentence_fields is not None:
        n[config.SENTENCE] = sentence_fields.sentence
        n[config.SENTENCE_FURIGANA] = sentence_fields.sentence_furigana
        n[config.CLOZE_BEFORE] = sentence_fields.cloze_before
        n[config.CLOZE_INSIDE] = sentence_fields.cloze_inside
        n[config.CLOZE_AFTER] = sentence_fields.cloze_after
    for t in tags:
        n.add_tag(t)
    col.add_note(n, col.decks.id(deck))
    return n.id


def card_tags(fields) -> list[str]:
    """Provenance + idempotency tags so nightly passes treat the card as settled:
    MINED_TAG always; ENRICHED_TAG (Meaning is dict-authoritative); EXAMPLES_TAG
    when an examples block was filled, else NO_EXAMPLE_TAG."""
    tags = [config.MINED_TAG, config.ENRICHED_TAG]
    tags.append(config.EXAMPLES_TAG if fields.examples else config.NO_EXAMPLE_TAG)
    return tags
```

- [ ] **Step 4: Run, expect pass**

Run: `uv run pytest tests/test_mine.py -v`
Expected: PASS.

- [ ] **Step 5: Add a `card_tags` test + commit**

```python
def test_card_tags_with_and_without_examples():
    from ankienrich import enrich_one
    f = enrich_one.Fields("a","a","a","","m","s","<div/>",None,None)
    assert config.EXAMPLES_TAG in mine.card_tags(f)
    f2 = enrich_one.Fields("a","a","a","","m","s","",None,None)
    assert config.NO_EXAMPLE_TAG in mine.card_tags(f2)
```

Run: `uv run pytest tests/test_mine.py -v` → PASS, then:

```bash
git add ankienrich/mine.py tests/test_mine.py
git commit -m "feat(mine): deck resolution, dup check, card creation + tagging"
```

---

## Task 8: `mine` — sentence options + `commit` orchestration

**Files:**
- Modify: `ankienrich/mine.py`
- Test: `tests/test_mine.py`

- [ ] **Step 1: Write failing tests**

```python
def test_sentence_options_uses_massif(monkeypatch):
    monkeypatch.setattr(mine.massif, "top_sentences",
                        lambda term, n=5: ["S1", "S2"])
    assert mine.sentence_options("箸", ex_conn=object()) == ["S1", "S2"]

def test_sentence_options_falls_back_to_local(monkeypatch):
    monkeypatch.setattr(mine.massif, "top_sentences", lambda term, n=5: [])
    monkeypatch.setattr(mine, "_local_sentences",
                        lambda conn, term, n: ["L1"])
    assert mine.sentence_options("箸", ex_conn=object()) == ["L1"]

def test_commit_refuses_duplicate(monkeypatch):
    # fake collection + sync so no anki/network needed
    class _Col:
        def close(self): pass
    monkeypatch.setattr(mine.collection, "open_working", lambda: _Col())
    monkeypatch.setattr(mine.sync, "login", lambda col: "auth")
    monkeypatch.setattr(mine.sync, "pull", lambda col, a: ("none", a))
    monkeypatch.setattr(mine, "existing_note", lambda col, t, r: 123)
    from ankienrich import enrich_one
    f = enrich_one.Fields("箸","はし","箸[はし]","","m","s","",None,None)
    res = mine.commit(f, None)
    assert res["status"] == "duplicate"
```

- [ ] **Step 2: Run, expect failure**

Run: `uv run pytest tests/test_mine.py -k "sentence_options or commit" -v`
Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `ankienrich/mine.py` (add imports at top: `from . import massif` and `from .examples import index as ex_index`):

```python
def _local_sentences(ex_conn, term: str, n: int) -> list[str]:
    """Fallback plain sentences from examples.sqlite. Uses the stored `plain`
    column across readings (the picker re-derives cloze per chosen sentence)."""
    rows = ex_conn.execute(
        "SELECT DISTINCT plain FROM examples WHERE term=? AND plain<>'' LIMIT ?",
        (term, n)).fetchall()
    return [r[0] for r in rows]


def sentence_options(term: str, *, ex_conn, n: int = config.MINE_SENTENCE_N) -> list[str]:
    """Top-n candidate sentences: Massif first, local examples.sqlite on failure."""
    hits = massif.top_sentences(term, n=n)
    if hits:
        return hits
    return _local_sentences(ex_conn, term, n)


def commit(fields, sentence_fields, *, deck: str | None = None) -> dict:
    """Pull → dup-check → backup → add → push, under the run lock. Returns a
    status dict. Raises runlock.Busy if a run holds the lock."""
    with runlock.acquire():
        col = collection.open_working()
        try:
            auth = sync.login(col)
            pull_action, auth = sync.pull(col, auth)
            if existing_note(col, fields.term, fields.reading) is not None:
                return {"status": "duplicate"}
            collection.backup(col)
            deck = deck or target_deck(col)
            nid = create_card(col, fields, sentence_fields, deck=deck,
                              tags=card_tags(fields))
            push_action, auth = sync.push(col, auth)
            return {"status": "ok", "nid": nid, "deck": deck,
                    "pull": pull_action, "push": push_action}
        finally:
            col.close()
```

- [ ] **Step 4: Run, expect pass**

Run: `uv run pytest tests/test_mine.py -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ankienrich/mine.py tests/test_mine.py
git commit -m "feat(mine): sentence options (massif+fallback) and commit orchestration"
```

---

## Task 9: `mine` — pure conversation helpers + `Session`

These are the UI-building pure functions `bot.py` calls; keeping them here makes them testable without telebot.

**Files:**
- Modify: `ankienrich/mine.py`
- Test: `tests/test_mine.py`

- [ ] **Step 1: Write failing tests**

```python
def test_candidate_rows_label_and_payload():
    from ankienrich.enrich_one import Candidate
    rows = mine.candidate_rows([Candidate("かど", "corner"), Candidate("つの", "horn")])
    assert rows[0] == ("かど — corner", "cand:0")
    assert rows[1] == ("つの — horn", "cand:1")

def test_sentence_rows_number_and_payload():
    rows = mine.sentence_rows(["AAA", "BBB"])
    assert rows[0][1] == "sent:0" and "AAA" in rows[0][0]
    assert rows[-1] == ("⏭ Skip sentence", "sent:skip")

def test_preview_text_includes_reading_meaning():
    from ankienrich.enrich_one import Fields
    f = Fields("箸","はし","箸[はし]","<svg/>","chopsticks","JMdict","",None,b"x")
    txt = mine.preview_text(f)
    assert "はし" in txt and "chopsticks" in txt and "JMdict" in txt

def test_session_defaults():
    s = mine.Session(chat_id=1)
    assert s.state == "idle" and s.candidates == [] and s.fields is None
```

- [ ] **Step 2: Run, expect failure**

Run: `uv run pytest tests/test_mine.py -k "candidate_rows or sentence_rows or preview_text or Session" -v`
Expected: FAIL.

- [ ] **Step 3: Implement**

Add to `ankienrich/mine.py`:

```python
import re as _re

_TAGS = _re.compile(r"<[^>]+>")


def _plain(s: str, limit: int) -> str:
    return _TAGS.sub("", s).strip()[:limit]


def candidate_rows(cands) -> list[tuple[str, str]]:
    """(button label, callback_data) per candidate reading."""
    return [(f"{c.reading} — {c.gloss}", f"cand:{i}") for i, c in enumerate(cands)]


def sentence_rows(sentences) -> list[tuple[str, str]]:
    rows = [(f"{i+1}. {_plain(s, 50)}", f"sent:{i}") for i, s in enumerate(sentences)]
    rows.append(("⏭ Skip sentence", "sent:skip"))
    return rows


def preview_text(fields) -> str:
    lines = [f"📖 {fields.term}（{fields.reading}）",
             "", _plain(fields.meaning, 300)]
    if fields.definition_source:
        lines.append(f"— {fields.definition_source}")
    lines.append("🔊 audio: " + ("yes" if fields.audio_clip else "—"))
    lines.append("📈 pitch: " + ("yes" if fields.pitch_svg else "—"))
    return "\n".join(lines)


def confirm_text(fields, sentence_fields) -> str:
    parts = [f"Add 「{fields.term}」（{fields.reading}）?"]
    if sentence_fields is not None:
        parts.append("例: " + _cloze_preview(sentence_fields))
    else:
        parts.append("(no sentence)")
    return "\n".join(parts)


def _cloze_preview(sf) -> str:
    return f"{sf.cloze_before}【{sf.cloze_inside}】{sf.cloze_after}"


@dataclass
class Session:
    chat_id: int
    state: str = "idle"               # idle|candidates|preview|sentence|confirm
    term: str = ""
    candidates: list = field(default_factory=list)
    fields: object | None = None
    sentences: list = field(default_factory=list)
    sentence_fields: object | None = None
```

- [ ] **Step 4: Run, expect pass**

Run: `uv run pytest tests/test_mine.py -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ankienrich/mine.py tests/test_mine.py
git commit -m "feat(mine): pure conversation UI helpers + Session"
```

---

## Task 10: `bot.py` wiring

Glue the flow into telebot. Heavy IO (index opening, `build_fields`, `commit`) happens here; logic is delegated to the tested `mine`/`enrich_one` helpers. The new handlers must not shadow the existing Run/Help buttons.

**Files:**
- Modify: `ankienrich/bot.py`
- Test: `tests/test_bot.py` (pure helpers only; conversation is manually smoke-tested)

- [ ] **Step 1: Read the current bot**

Read `ankienrich/bot.py` fully. Note: existing handlers are `commands=["start","help"]`, `commands=["run"]`, two `func=lambda m: m.text == ...` button handlers, and a catch-all `callback_query_handler`. The new word handler must be registered so it only fires for free text that is NOT a known button (`RUN_BUTTON`, `HELP_BUTTON`) and NOT a command.

- [ ] **Step 2: Add index-opening + a per-chat session store**

Add near the top of `build_bot`:

```python
from . import enrich_one, mine, runlock
from .defs import index as defs_index
from .examples import index as ex_index
from .audio import index as audio_index
from .jrp_fill import make_generator
from .jrp_fill.accent import AccentIndex

_SESSIONS: dict[int, mine.Session] = {}
```

Open the read indexes lazily once (module-level singletons) — they are read-only sqlite + the accent index:

```python
def _indexes():
    """(defs_conn, ex_conn, audio_conn, acc, generate), opened once."""
    if not hasattr(_indexes, "_cache"):
        _indexes._cache = (
            defs_index.open_index(), ex_index.open_index(),
            audio_index.open_index(), AccentIndex.load(), make_generator())
    return _indexes._cache
```

- [ ] **Step 3: Word handler → candidates/preview**

Register AFTER the button handlers so they take precedence:

```python
@bot.message_handler(func=lambda m: bool(m.text) and not m.text.startswith("/")
                     and m.text not in (RUN_BUTTON, HELP_BUTTON))
def _word(m):
    if not _authorized(m.chat.id, chat_id):
        return
    term = m.text.strip()
    defs_conn, ex_conn, audio_conn, acc, gen = _indexes()
    cands = enrich_one.candidates(defs_conn, term)
    if not cands:
        bot.send_message(m.chat.id, f"「{term}」not found — nothing to add.")
        return
    s = mine.Session(chat_id=m.chat.id, term=term, candidates=cands)
    _SESSIONS[m.chat.id] = s
    if len(cands) == 1:
        _enter_preview(m.chat.id, 0)
    else:
        s.state = "candidates"
        kb = _inline(mine.candidate_rows(cands))
        bot.send_message(m.chat.id, f"「{term}」— pick a reading:", reply_markup=kb)
```

Helper to build an inline keyboard from `(label, payload)` rows (reuse the `btn` style already in the file):

```python
def _inline(rows):
    kb = telebot.types.InlineKeyboardMarkup()
    for label, payload in rows:
        kb.add(telebot.types.InlineKeyboardButton(label, callback_data=payload))
    return kb
```

- [ ] **Step 4: `_enter_preview` — build fields, dup-check, send audio**

```python
def _enter_preview(chat_id_, cand_idx):
    s = _SESSIONS[chat_id_]
    reading = s.candidates[cand_idx].reading
    defs_conn, ex_conn, audio_conn, acc, gen = _indexes()
    # authoritative dup-check needs the collection; do a cheap best-effort here.
    fields = enrich_one.build_fields(
        s.term, reading, defs_conn=defs_conn, ex_conn=ex_conn,
        audio_conn=audio_conn, acc=acc)
    s.fields = fields
    s.state = "preview"
    if fields.audio_clip:
        bot.send_voice(chat_id_, fields.audio_clip)
    kb = _inline([("📝 Pick sentence", "go:sentence"),
                  ("⏭ Skip sentence", "go:skip"),
                  ("✖ Cancel", "go:cancel")])
    bot.send_message(chat_id_, mine.preview_text(fields), reply_markup=kb)
```

- [ ] **Step 5: Callback router for the mining flow**

The existing `callback_query_handler` handles `toggle:/all/apply/cancel` for the run menu. Extend it (or add a dispatch at its top) to handle the new prefixes `cand:`, `go:`, `sent:` when the chat has an active session. Pseudocode to insert at the start of `_cb`:

```python
data = c.data
s = _SESSIONS.get(c.message.chat.id)
if data.startswith("cand:"):
    _enter_preview(c.message.chat.id, int(data.split(":")[1]))
    bot.answer_callback_query(c.id); return
if data == "go:cancel":
    _SESSIONS.pop(c.message.chat.id, None)
    bot.answer_callback_query(c.id, "Cancelled"); return
if data == "go:sentence" and s:
    _, ex_conn, *_ = _indexes()
    s.sentences = mine.sentence_options(s.term, ex_conn=ex_conn)
    s.state = "sentence"
    bot.send_message(c.message.chat.id, "Pick a sentence:",
                     reply_markup=_inline(mine.sentence_rows(s.sentences)))
    bot.answer_callback_query(c.id); return
if data == "go:skip" and s:
    s.sentence_fields = None
    _enter_confirm(c.message.chat.id); bot.answer_callback_query(c.id); return
if data.startswith("sent:") and s:
    *_, acc, gen = _indexes()
    if data == "sent:skip":
        s.sentence_fields = None
    else:
        plain = s.sentences[int(data.split(":")[1])]
        s.sentence_fields = enrich_one.fields_for_sentence(
            s.term, s.fields.reading, plain, generate=gen, acc=acc)
    _enter_confirm(c.message.chat.id); bot.answer_callback_query(c.id); return
if data == "go:apply" and s:
    _apply_mine(c.message.chat.id); bot.answer_callback_query(c.id, "Adding…"); return
```

With:

```python
def _enter_confirm(chat_id_):
    s = _SESSIONS[chat_id_]; s.state = "confirm"
    kb = _inline([("✅ Apply", "go:apply"), ("✖ Cancel", "go:cancel")])
    bot.send_message(chat_id_, mine.confirm_text(s.fields, s.sentence_fields),
                     reply_markup=kb)

def _apply_mine(chat_id_):
    s = _SESSIONS.pop(chat_id_, None)
    if not s:
        return
    try:
        res = mine.commit(s.fields, s.sentence_fields)
    except runlock.Busy:
        bot.send_message(chat_id_, "⏳ A run is in progress — try again shortly.")
        return
    if res["status"] == "duplicate":
        bot.send_message(chat_id_, "Already in the deck — nothing added.")
    else:
        bot.send_message(chat_id_, f"✅ Added 「{s.term}」to {res['deck']} & synced.")
```

(Phase-2 image step would slot between sentence selection and `_enter_confirm`.)

- [ ] **Step 6: Update HELP_TEXT**

Add a line to the existing `HELP_TEXT` telling the user they can just send a word to add it (so the new entry point is discoverable).

- [ ] **Step 7: Add a pure-helper bot test**

The conversation needs live telebot/network, so keep the automated test to a pure helper. In `tests/test_bot.py`:

```python
def test_inline_builds_one_button_per_row():
    import ankienrich.bot as bot
    # _inline is defined inside build_bot; if so, lift it to module scope so it's
    # importable, or test mine.candidate_rows/sentence_rows directly here instead.
```

If `_inline` is closure-local, instead assert on `mine.candidate_rows`/`mine.sentence_rows` here (already covered in Task 9) and rely on manual smoke for the wiring. Prefer lifting `_inline` to module scope for testability.

- [ ] **Step 8: Run the bot test suite**

Run: `uv run pytest tests/test_bot.py -v`
Expected: PASS (existing tests unaffected).

- [ ] **Step 9: Manual smoke test**

With `ANKIWEB_USERNAME`/`ANKIWEB_PASSWORD`/`TELEGRAM_*` set:

```bash
uv run anki-enrich-bot   # then in Telegram:
#  send 勉強 → pick reading (if asked) → preview (audio plays) →
#  Pick sentence → choose 1 → Apply → "✅ Added … & synced"
```

Verify in Anki that the card exists with Meaning/Pitch/Audio/Sentence/Cloze/Examples populated and the `ankienrich::mined` tag.

- [ ] **Step 10: Commit**

```bash
git add ankienrich/bot.py tests/test_bot.py
git commit -m "feat(bot): single-word mining flow (word → preview → sentence → add+sync)"
```

---

## Task 11: Docs + branch wrap-up

**Files:**
- Modify: `CLAUDE.md` (Running section)

- [ ] **Step 1: Document the feature**

Add to `CLAUDE.md` under "Running": a short note that the bot accepts a free-text word to mine a new card (preview → Massif sentence → add+sync), images deferred.

- [ ] **Step 2: Full suite green**

Run: `uv run pytest -q`
Expected: all pass.

- [ ] **Step 3: Commit + delete the throwaway plan**

Per the project's doc workflow (plans are throwaway, deleted when green):

```bash
git rm docs/superpowers/plans/2026-06-17-mine-word-to-card.md
git add CLAUDE.md
git commit -m "docs: document single-word mining; drop completed plan"
```

(Keep the spec; it's the durable design record.)

---

## Self-review notes (resolved)

- **Spec coverage:** disambiguation (T4 `candidates`, T10 candidate keyboard), shared single-note path (T5/T6, used by preview in T10 and commit in T7/T8), Massif + fallback (T2, T8 `sentence_options`), deck+tag (T7), immediate sync under runlock (T8 `commit`), 0-hit refuse (T10 `_word`), `requests`+notify migration (T1). All covered.
- **Type consistency:** `Fields`/`SentenceFields`/`Candidate`/`Session` field names are identical across tasks; `build_fields`/`fields_for_sentence`/`create_card`/`commit`/`sentence_options` signatures match their callers in T10.
- **Known soft spots flagged inline:** exact `jrp_to_furigana` spacing (T6 note), `_inline` closure-vs-module scope for testability (T10 step 7), Massif markup pinned by an embedded fixture rather than a live capture (T2).

---

# Image integration addendum (2026-06-17)

`ankienrich/images.py` (merged from `main`) provides:
```python
search_images(term, limit=12, *, timeout_ms=8000) -> list[ImageCandidate]
# ImageCandidate(thumb_url, full_url, title, source_page); [] on timeout/no-results.
```
It ONLY searches. This addendum adds: capped/safe wrapping, downloading the chosen
`full_url`, a Telegram image-picker step, and writing the `Image` field. The
`Image` field format (verified against a real card) is `<img src="FILENAME.jpg">`.

Config constants to add in Task 7 (alongside the others, in `ankienrich/config.py`):
```python
IMAGE = "Image"            # Kinomoto field
MINE_IMAGE_N = 6           # thumbnails offered in the picker
IMAGE_DL_TIMEOUT = 8       # seconds to download the chosen full_url
```

## Task 7 delta — `create_card` gains an optional image

Add `IMAGE`, `MINE_IMAGE_N`, `IMAGE_DL_TIMEOUT` to config in Step 1.

`create_card` signature becomes:
```python
def create_card(col, fields, sentence_fields, *, deck: str, tags, image=None) -> int:
```
where `image` is `tuple[bytes, str] | None` = `(data, ext)`. After the audio block,
before `add_note`:
```python
    if image is not None:
        data, ext = image
        fname = col.media.write_data(f"mined_{fields.term}.{ext}", data)
        n[config.IMAGE] = f'<img src="{fname}">'
```
Add a test asserting the Image field + media write:
```python
def test_create_card_with_image_writes_field_and_media(tmp_path):
    col = _col(tmp_path)
    fields = enrich_one.Fields("箸","はし","箸[はし]","","chopsticks","JMdict","",None,None)
    nid = mine.create_card(col, fields, None, deck="言葉文章",
                           tags=[config.MINED_TAG], image=(b"JPEGDATA", "jpg"))
    n = col.get_note(nid)
    assert n["Image"].startswith('<img src="') and n["Image"].endswith('">')
    assert "mined_箸" in n["Image"]
```
The existing `test_create_card_without_sentence_leaves_those_fields_empty` should
also assert `n["Image"] == ""` (default image=None path).

## Task 8 delta — image helpers + `commit` threads the image

Add to `ankienrich/mine.py` (imports: `from . import images` and `from urllib.parse import urlparse`):
```python
def image_candidates(term: str, n: int = config.MINE_IMAGE_N):
    """Up to n Bing image candidates for `term`. Never raises: a missing browser,
    Playwright import error, or any search failure yields []."""
    try:
        return images.search_images(term, limit=n)
    except Exception:
        return []


def _ext_from_url(url: str) -> str:
    suffix = urlparse(url).path.rsplit(".", 1)
    ext = suffix[1].lower() if len(suffix) == 2 else ""
    return ext if ext in {"jpg", "jpeg", "png", "webp", "gif"} else "jpg"


def download_image(url: str, *, timeout: int = config.IMAGE_DL_TIMEOUT):
    """Download the chosen image. Returns (bytes, ext) or None on any failure /
    non-image content."""
    try:
        resp = requests.get(url, headers={"User-Agent": "Mozilla/5.0 (anki-enrich)"},
                            timeout=timeout)
        resp.raise_for_status()
        if not resp.headers.get("Content-Type", "").startswith("image/"):
            return None
        return resp.content, _ext_from_url(url)
    except requests.RequestException:
        return None
```
(`requests` is already imported in mine.py for nothing yet — add `import requests` at top.)

`commit` signature becomes `commit(fields, sentence_fields, *, deck=None, image=None)`
and passes `image=image` to `create_card(...)`.

Tests to add in `tests/test_mine.py`:
```python
def test_image_candidates_caps_and_swallows_errors(monkeypatch):
    from ankienrich.images import ImageCandidate
    monkeypatch.setattr(mine.images, "search_images",
                        lambda term, limit=6: [ImageCandidate("t","f","","")] )
    assert len(mine.image_candidates("箸")) == 1
    def _boom(*a, **k): raise RuntimeError("no browser")
    monkeypatch.setattr(mine.images, "search_images", _boom)
    assert mine.image_candidates("箸") == []

def test_download_image_returns_bytes_and_ext(monkeypatch):
    class _R:
        status_code = 200
        headers = {"Content-Type": "image/jpeg"}
        content = b"JPEG"
        def raise_for_status(self): pass
    monkeypatch.setattr(mine.requests, "get", lambda u, **k: _R())
    assert mine.download_image("http://x/y/pic.png") == (b"JPEG", "png")

def test_download_image_none_on_failure(monkeypatch):
    def _boom(*a, **k): raise mine.requests.RequestException("down")
    monkeypatch.setattr(mine.requests, "get", _boom)
    assert mine.download_image("http://x/pic.jpg") is None

def test_download_image_none_on_non_image(monkeypatch):
    class _R:
        headers = {"Content-Type": "text/html"}
        def raise_for_status(self): pass
    monkeypatch.setattr(mine.requests, "get", lambda u, **k: _R())
    assert mine.download_image("http://x/pic.jpg") is None
```
Update the existing `test_commit_refuses_duplicate` (and any commit test) only if it
positionally passes args — `image` is keyword-only with a default, so existing calls
are unaffected.

## Task 9 delta — Session + image rows

`Session` gains two fields:
```python
    image_candidates: list = field(default_factory=list)
    image: tuple | None = None        # (bytes, ext) once downloaded
```
Add a pure UI helper + test:
```python
def image_rows(cands) -> list[tuple[str, str]]:
    rows = [(f"{i+1}", f"img:{i}") for i in range(len(cands))]
    rows.append(("⏭ Skip image", "img:skip"))
    return rows
```
```python
def test_image_rows_number_and_skip():
    from ankienrich.images import ImageCandidate
    cands = [ImageCandidate("t1","f1","",""), ImageCandidate("t2","f2","","")]
    rows = mine.image_rows(cands)
    assert rows[0] == ("1", "img:0") and rows[-1] == ("⏭ Skip image", "img:skip")
```
`confirm_text` should mention whether an image is attached, e.g. append
`"🖼 image: yes"` when `session.image is not None` — but `confirm_text(fields, sentence_fields)`
doesn't take the session; instead add the image line in the bot's confirm step, OR
extend `confirm_text(fields, sentence_fields, has_image=False)`. Choose the latter:
```python
def confirm_text(fields, sentence_fields, has_image: bool = False) -> str:
    parts = [f"Add 「{fields.term}」（{fields.reading}）?"]
    parts.append("例: " + _cloze_preview(sentence_fields) if sentence_fields is not None
                 else "(no sentence)")
    parts.append("🖼 image: " + ("yes" if has_image else "—"))
    return "\n".join(parts)
```
Update the Task 9 `confirm_text` test accordingly if present (it asserts text only).

## Task 10 delta — bot image-picker step

Insert an IMAGE state between the sentence step and confirm. After the sentence
callback resolves (`go:skip`, `sent:n`, `sent:skip`) call `_enter_image(chat_id)`
instead of `_enter_confirm(chat_id)`:
```python
def _enter_image(chat_id_):
    s = _SESSIONS[chat_id_]
    s.image_candidates = mine.image_candidates(s.term)
    if not s.image_candidates:
        bot.send_message(chat_id_, "No images found — skipping image.")
        _enter_confirm(chat_id_); return
    s.state = "image"
    media = [telebot.types.InputMediaPhoto(c.thumb_url, caption=str(i + 1))
             for i, c in enumerate(s.image_candidates)]
    try:
        bot.send_media_group(chat_id_, media)
    except Exception:
        pass  # thumbnails are a nicety; the numbered buttons still drive the pick
    bot.send_message(chat_id_, "Pick an image:",
                     reply_markup=_inline(mine.image_rows(s.image_candidates)))
```
Add to the callback router:
```python
if data.startswith("img:") and s:
    if data == "img:skip":
        s.image = None
    else:
        cand = s.image_candidates[int(data.split(":")[1])]
        s.image = mine.download_image(cand.full_url)
        if s.image is None:
            bot.send_message(c.message.chat.id, "Couldn't fetch that image — skipping.")
    _enter_confirm(c.message.chat.id); bot.answer_callback_query(c.id); return
```
`_enter_confirm` passes the image flag:
```python
def _enter_confirm(chat_id_):
    s = _SESSIONS[chat_id_]; s.state = "confirm"
    kb = _inline([("✅ Apply", "go:apply"), ("✖ Cancel", "go:cancel")])
    bot.send_message(chat_id_, mine.confirm_text(s.fields, s.sentence_fields,
                                                 has_image=s.image is not None),
                     reply_markup=kb)
```
`_apply_mine` passes the image to commit: `mine.commit(s.fields, s.sentence_fields, image=s.image)`.

## Task 11 delta — docs + deploy note

In `CLAUDE.md` mention the image step (Bing search, pick or skip). Add a NOTE that
the deployed bot needs Chromium: `uv run playwright install --with-deps chromium`
in the Hetzner Docker image (see `docs/image-search-handoff.md`) — flag as a
follow-up, do NOT modify the Dockerfile in this branch unless asked. Do not delete
`docs/image-search-handoff.md`.
