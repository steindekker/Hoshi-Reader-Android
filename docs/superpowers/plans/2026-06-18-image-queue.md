# Image Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an on-demand Telegram image queue that walks newly-added imageless cards one at a time, reusing the mining flow's Bing image picker to fill the `Image` field.

**Architecture:** A new `ankienrich/imagequeue.py` owns all queue policy (flagging, candidate selection, per-card sync-guarded writes, pure conversation state). The defs pass reports which nids it first-enriched; `pipeline.py` feeds those to `imagequeue.flag_new` so new imageless cards get an `ankienrich::needs-image` tag. `bot.py` gains a `🖼 画像` button and a queue conversation that reuses `mine`'s image helpers verbatim.

**Tech Stack:** Python 3.11+, `anki` library, `pytest`, `pyTelegramBotAPI` (telebot), Playwright/Chromium (already a dep for the mining flow).

**Spec:** `docs/superpowers/specs/2026-06-17-image-queue-design.md`

---

## File Structure

- **Create** `ankienrich/imagequeue.py` — all queue policy, no telebot imports: `Candidate` + `QueueSession` dataclasses, `flag_new`, `candidates`, `open_queue`, `set_image`, `skip`, and pure UI helpers (`queue_image_rows`, `summary_text`).
- **Create** `tests/test_imagequeue.py` — unit tests for the above.
- **Modify** `ankienrich/config.py` — add `NEEDS_IMAGE_TAG`, `NO_IMAGE_TAG`, `IMAGE_BUTTON`.
- **Modify** `ankienrich/defs/enrich.py` — `enrich_notes` returns `first_enriched_nids`.
- **Modify** `tests/test_enrich.py` — assert `first_enriched_nids` content + idempotency.
- **Modify** `ankienrich/pipeline.py` — after the enrich step on apply, call `imagequeue.flag_new` and surface the flagged count.
- **Modify** `tests/test_pipeline.py` — assert `flag_new` wiring (apply vs dry-run).
- **Modify** `ankienrich/bot.py` — `🖼 画像` button, `_word` guard exclusion, queue conversation, `q…` callback dispatch.
- **Modify** `tests/test_bot.py` — assert button present + pure queue-helper behaviour.

---

## Task 1: Config constants

**Files:**
- Modify: `ankienrich/config.py` (near the existing tag block, lines ~31-49 and ~69-72)
- Test: `tests/test_imagequeue.py` (new file — first test lives here)

- [ ] **Step 1: Write the failing test**

Create `tests/test_imagequeue.py`:

```python
from ankienrich import config


def test_image_queue_constants_exist():
    assert config.NEEDS_IMAGE_TAG == "ankienrich::needs-image"
    assert config.NO_IMAGE_TAG == "ankienrich::no-image"
    assert config.IMAGE_BUTTON == "🖼 画像"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run pytest tests/test_imagequeue.py::test_image_queue_constants_exist -v`
Expected: FAIL with `AttributeError: module 'ankienrich.config' has no attribute 'NEEDS_IMAGE_TAG'`

- [ ] **Step 3: Add the constants**

In `ankienrich/config.py`, next to the existing `NO_EXAMPLE_TAG` / `MINED_TAG` tag definitions, add:

```python
NEEDS_IMAGE_TAG = "ankienrich::needs-image"   # image-queue selector; set at first enrich
NO_IMAGE_TAG = "ankienrich::no-image"         # permanent skip from the image queue
```

And near the `IMAGE` field constant (line ~69), add:

```python
IMAGE_BUTTON = "🖼 画像"   # persistent reply-keyboard button that opens the image queue
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run pytest tests/test_imagequeue.py::test_image_queue_constants_exist -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ankienrich/config.py tests/test_imagequeue.py
git commit -m "feat(imagequeue): config tags + button constant"
```

---

## Task 2: `imagequeue.flag_new` + `Candidate` + `candidates`

`flag_new` tags first-enriched imageless cards; `candidates` selects tagged cards for the queue. Both take an already-open collection.

**Files:**
- Create: `ankienrich/imagequeue.py`
- Test: `tests/test_imagequeue.py`

- [ ] **Step 1: Write the failing tests**

Add to `tests/test_imagequeue.py` (reuse the Kinomoto-collection helper from `test_enrich.py` — copy it in, the project already inlines this helper per test module):

```python
import pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankienrich import config, imagequeue

KINOMOTO_FIELDS = [
    config.TERM, config.READING, config.SENTENCE, config.DEFINITION, config.IMAGE,
]


def _new_notetype(col, name, fields):
    m = col.models.new(name)
    for fname in fields:
        col.models.add_field(m, col.models.new_field(fname))
    t = col.models.new_template("Card 1")
    t["qfmt"], t["afmt"] = "{{%s}}" % fields[0], "{{FrontSide}}"
    col.models.add_template(m, t)
    col.models.add(m)
    return col.models.by_name(name)


@pytest.fixture
def kcol(tmp_path):
    c = Collection(str(tmp_path / "k.anki2"))
    _new_notetype(c, config.NOTETYPE, KINOMOTO_FIELDS)
    yield c
    c.close()


def _add(col, term, reading, *, image="", sentence="", meaning="", tags=()):
    m = col.models.by_name(config.NOTETYPE)
    col.models.set_current(m)
    n = col.new_note(m)
    n[config.TERM] = term
    n[config.READING] = reading
    n[config.IMAGE] = image
    n[config.SENTENCE] = sentence
    n[config.DEFINITION] = meaning
    for t in tags:
        n.add_tag(t)
    col.add_note(n, col.decks.id("言葉文章"))
    return n.id


def test_flag_new_tags_only_imageless_unflagged(kcol):
    a = _add(kcol, "迷惑", "めいわく")                                   # flag
    b = _add(kcol, "箸", "はし", image='<img src="x.jpg">')             # has image: skip
    c = _add(kcol, "橋", "はし", tags=[config.NO_IMAGE_TAG])             # already excluded
    d = _add(kcol, "端", "はし", tags=[config.NEEDS_IMAGE_TAG])         # already flagged
    n = imagequeue.flag_new(kcol, [a, b, c, d])
    assert n == 1
    assert config.NEEDS_IMAGE_TAG in kcol.get_note(a).tags
    assert config.NEEDS_IMAGE_TAG not in kcol.get_note(b).tags
    assert config.NEEDS_IMAGE_TAG not in kcol.get_note(c).tags


def test_candidates_returns_flagged_in_nid_order_with_context(kcol):
    a = _add(kcol, "迷惑", "めいわく", sentence="迷惑をかける", tags=[config.NEEDS_IMAGE_TAG])
    _add(kcol, "箸", "はし")  # untagged: excluded
    b = _add(kcol, "橋", "はし", meaning="a bridge", tags=[config.NEEDS_IMAGE_TAG])
    out = imagequeue.candidates(kcol)
    assert [c.nid for c in out] == sorted([a, b])
    by_nid = {c.nid: c for c in out}
    assert by_nid[a].term == "迷惑" and by_nid[a].reading == "めいわく"
    assert "迷惑をかける" in by_nid[a].context        # from Sentence
    assert "a bridge" in by_nid[b].context           # falls back to Meaning
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_imagequeue.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'ankienrich.imagequeue'`

- [ ] **Step 3: Create `ankienrich/imagequeue.py` with the dataclass + both functions**

```python
"""On-demand image queue: flag newly-added imageless cards, then walk them one at a
time in the Telegram bot to fill the Image field. All queue policy lives here; the
bot only drives the conversation. No telebot imports."""
from __future__ import annotations

import re as _re
from dataclasses import dataclass, field

from . import collection, config, runlock, sync

_TAGS = _re.compile(r"<[^>]+>")


def _plain(html: str, limit: int = 60) -> str:
    """Tags stripped, whitespace collapsed, truncated — a glanceable context line."""
    text = " ".join(_TAGS.sub(" ", html or "").split())
    return text[:limit]


@dataclass(frozen=True)
class Candidate:
    nid: int
    term: str
    reading: str
    context: str


def _fields(col) -> set[str]:
    return {f["name"] for f in col.models.by_name(config.NOTETYPE)["flds"]}


def flag_new(col, nids) -> int:
    """Tag each imageless, not-yet-processed note with NEEDS_IMAGE_TAG. A note is
    skipped if it already has an Image, or already carries needs-image/no-image.
    Returns the count newly flagged. Caller's pull/push envelope persists the tags."""
    flds = _fields(col)
    flagged = 0
    for nid in nids:
        note = col.get_note(nid)
        if config.IMAGE in flds and note[config.IMAGE].strip():
            continue
        if config.NEEDS_IMAGE_TAG in note.tags or config.NO_IMAGE_TAG in note.tags:
            continue
        note.add_tag(config.NEEDS_IMAGE_TAG)
        col.update_note(note)
        flagged += 1
    return flagged


def candidates(col) -> list[Candidate]:
    """Flagged notes, oldest first (nid == creation timestamp). Context is a short
    plain-text snippet from Sentence, falling back to Meaning."""
    flds = _fields(col)
    q = f'{collection.notetype_search()} tag:{config.NEEDS_IMAGE_TAG}'
    out = []
    for nid in sorted(col.find_notes(q)):
        note = col.get_note(nid)
        sentence = note[config.SENTENCE] if config.SENTENCE in flds else ""
        meaning = note[config.DEFINITION] if config.DEFINITION in flds else ""
        out.append(Candidate(
            nid=nid,
            term=note[config.TERM].strip() if config.TERM in flds else "",
            reading=note[config.READING].strip() if config.READING in flds else "",
            context=_plain(sentence or meaning),
        ))
    return out
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest tests/test_imagequeue.py -v`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add ankienrich/imagequeue.py tests/test_imagequeue.py
git commit -m "feat(imagequeue): flag_new + candidate selection"
```

---

## Task 3: `imagequeue.set_image` and `skip`

Per-card sync-guarded mutations mirroring `mine.commit`: `runlock` → open → login → pull → write → push → close.

**Files:**
- Modify: `ankienrich/imagequeue.py`
- Test: `tests/test_imagequeue.py`

- [ ] **Step 1: Write the failing tests**

Add to `tests/test_imagequeue.py`:

```python
import contextlib


def _stub_sync(monkeypatch, col):
    """Point imagequeue's sync envelope at an open temp collection without network
    or runlock, and keep the handle open so the test can assert on it afterward."""
    monkeypatch.setattr(imagequeue.runlock, "acquire", lambda: contextlib.nullcontext())
    monkeypatch.setattr(imagequeue.collection, "open_working", lambda: col)
    monkeypatch.setattr(imagequeue.sync, "login", lambda c: "auth")
    monkeypatch.setattr(imagequeue.sync, "pull", lambda c, a: ("none", a))
    monkeypatch.setattr(imagequeue.sync, "push", lambda c, a: ("ok", a))
    monkeypatch.setattr(col, "close", lambda: None)   # set_image/skip close in finally


def test_set_image_writes_field_media_and_clears_tag(kcol, monkeypatch):
    nid = _add(kcol, "迷惑", "めいわく", tags=[config.NEEDS_IMAGE_TAG])
    _stub_sync(monkeypatch, kcol)
    res = imagequeue.set_image(nid, (b"\x89PNGfake", "png"))
    assert res["status"] == "ok"
    note = kcol.get_note(nid)
    assert note[config.IMAGE] == f'<img src="q_{nid}.png">'
    assert config.NEEDS_IMAGE_TAG not in note.tags
    assert kcol.media.have(f"q_{nid}.png")


def test_set_image_gone_when_nid_absent(kcol, monkeypatch):
    _stub_sync(monkeypatch, kcol)
    res = imagequeue.set_image(99999999, (b"x", "jpg"))
    assert res["status"] == "gone"


def test_skip_swaps_needs_image_for_no_image(kcol, monkeypatch):
    nid = _add(kcol, "迷惑", "めいわく", tags=[config.NEEDS_IMAGE_TAG])
    _stub_sync(monkeypatch, kcol)
    res = imagequeue.skip(nid)
    assert res["status"] == "ok"
    note = kcol.get_note(nid)
    assert config.NEEDS_IMAGE_TAG not in note.tags
    assert config.NO_IMAGE_TAG in note.tags
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_imagequeue.py -k "set_image or skip" -v`
Expected: FAIL with `AttributeError: module 'ankienrich.imagequeue' has no attribute 'set_image'`

- [ ] **Step 3: Implement `open_queue`, `set_image`, `skip`**

Append to `ankienrich/imagequeue.py`:

```python
def _load(col, nid):
    """The note for nid, or None if it vanished remotely between OPEN and the pick."""
    try:
        return col.get_note(nid)
    except Exception:
        return None


def open_queue() -> list[Candidate]:
    """The single OPEN pull, encapsulated so bot.py stays thin. runlock → open →
    pull → candidates → close. Raises runlock.Busy if a run holds the lock."""
    with runlock.acquire():
        col = collection.open_working()
        try:
            auth = sync.login(col)
            _, auth = sync.pull(col, auth)
            return candidates(col)
        finally:
            col.close()


def set_image(nid: int, image) -> dict:
    """Write Image + remove needs-image for one note, under the run lock.
    `image` is (bytes, ext). Returns a status dict; "gone" if the note is absent."""
    with runlock.acquire():
        col = collection.open_working()
        try:
            auth = sync.login(col)
            _, auth = sync.pull(col, auth)
            note = _load(col, nid)
            if note is None:
                return {"status": "gone"}
            data, ext = image
            fname = col.media.write_data(f"q_{nid}.{ext}", data)
            note[config.IMAGE] = f'<img src="{fname}">'
            if config.NEEDS_IMAGE_TAG in note.tags:
                note.remove_tag(config.NEEDS_IMAGE_TAG)
            col.update_note(note)
            _, auth = sync.push(col, auth)
            return {"status": "ok", "nid": nid, "file": fname}
        finally:
            col.close()


def skip(nid: int) -> dict:
    """Swap needs-image for no-image (permanent exclusion), under the run lock."""
    with runlock.acquire():
        col = collection.open_working()
        try:
            auth = sync.login(col)
            _, auth = sync.pull(col, auth)
            note = _load(col, nid)
            if note is None:
                return {"status": "gone"}
            if config.NEEDS_IMAGE_TAG in note.tags:
                note.remove_tag(config.NEEDS_IMAGE_TAG)
            note.add_tag(config.NO_IMAGE_TAG)
            col.update_note(note)
            _, auth = sync.push(col, auth)
            return {"status": "ok", "nid": nid}
        finally:
            col.close()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest tests/test_imagequeue.py -v`
Expected: PASS (all imagequeue tests so far)

- [ ] **Step 5: Commit**

```bash
git add ankienrich/imagequeue.py tests/test_imagequeue.py
git commit -m "feat(imagequeue): open_queue + set_image + skip (sync-guarded)"
```

---

## Task 4: `QueueSession` + pure UI helpers

Pure conversation state and button rows, testable without telebot. Callback prefix is `q…` so it never collides with the mining `cand:/go:/sent:/img:` prefixes.

**Files:**
- Modify: `ankienrich/imagequeue.py`
- Test: `tests/test_imagequeue.py`

- [ ] **Step 1: Write the failing tests**

Add to `tests/test_imagequeue.py`:

```python
def test_queue_image_rows_numbered_then_skip_then_end():
    rows = imagequeue.queue_image_rows([object(), object()])
    assert rows[0] == ("1", "qpick:0")
    assert rows[1] == ("2", "qpick:1")
    assert rows[-2] == ("⏭ スキップ", "qskip")
    assert rows[-1] == ("■ 終了", "qend")


def test_queue_session_transitions_and_summary():
    c = imagequeue.Candidate(1, "a", "あ", "ctx")
    d = imagequeue.Candidate(2, "b", "い", "ctx")
    q = imagequeue.QueueSession(cards=[c, d])
    assert not q.done and q.current is c
    q.record_image()                      # imaged the first card
    assert q.current is d and q.imaged == 1
    q.record_skip()                       # skipped the second
    assert q.done and q.skipped == 1
    assert q.current is None
    assert imagequeue.summary_text(q) == "完了（imaged 1・skipped 1）"


def test_queue_session_advance_does_not_count():
    q = imagequeue.QueueSession(cards=[imagequeue.Candidate(1, "a", "あ", "")])
    q.advance()                           # auto-skip (browser down): index only
    assert q.done and q.imaged == 0 and q.skipped == 0
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_imagequeue.py -k "queue_image_rows or queue_session" -v`
Expected: FAIL with `AttributeError: module 'ankienrich.imagequeue' has no attribute 'queue_image_rows'`

- [ ] **Step 3: Implement the dataclass + helpers**

Append to `ankienrich/imagequeue.py`:

```python
@dataclass
class QueueSession:
    cards: list[Candidate]
    index: int = 0
    imaged: int = 0
    skipped: int = 0
    candidates: list = field(default_factory=list)   # current card's image hits

    @property
    def done(self) -> bool:
        return self.index >= len(self.cards)

    @property
    def current(self) -> Candidate | None:
        return None if self.done else self.cards[self.index]

    def advance(self) -> None:
        """Move past the current card without counting it (auto-skip / gone)."""
        self.index += 1

    def record_image(self) -> None:
        self.imaged += 1
        self.index += 1

    def record_skip(self) -> None:
        self.skipped += 1
        self.index += 1


def queue_image_rows(cands) -> list[tuple[str, str]]:
    rows = [(str(i + 1), f"qpick:{i}") for i in range(len(cands))]
    rows.append(("⏭ スキップ", "qskip"))
    rows.append(("■ 終了", "qend"))
    return rows


def summary_text(q: QueueSession) -> str:
    return f"完了（imaged {q.imaged}・skipped {q.skipped}）"
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest tests/test_imagequeue.py -v`
Expected: PASS (all imagequeue tests)

- [ ] **Step 5: Commit**

```bash
git add ankienrich/imagequeue.py tests/test_imagequeue.py
git commit -m "feat(imagequeue): QueueSession + pure UI helpers"
```

---

## Task 5: `enrich_notes` returns `first_enriched_nids`

The defs pass already computes `first_time` per note; collect and return the nids it first-enriched this run. No image logic here.

**Files:**
- Modify: `ankienrich/defs/enrich.py:107-161`
- Test: `tests/test_enrich.py`

- [ ] **Step 1: Write the failing test**

Add to `tests/test_enrich.py` (the `kcol` fixture and `_add_kino` / `_make_db` helpers already exist in that file):

```python
def test_enrich_notes_reports_first_enriched_nids(kcol, tmp_path):
    db = str(tmp_path / "defs.sqlite"); _make_db(db)
    nid = _add_kino(kcol, "迷惑", "めいわく")     # has J-J → gets enriched first time
    conn = index.open_index(db)
    res = enrich.enrich_notes(kcol, conn)
    assert res["first_enriched_nids"] == [nid]
    # idempotent: a re-run enriches nothing new
    res2 = enrich.enrich_notes(kcol, conn)
    assert res2["first_enriched_nids"] == []
    conn.close()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run pytest tests/test_enrich.py::test_enrich_notes_reports_first_enriched_nids -v`
Expected: FAIL with `KeyError: 'first_enriched_nids'`

- [ ] **Step 3: Collect and return the nids**

In `ankienrich/defs/enrich.py`, inside `enrich_notes`, initialise a list alongside `written` (line ~123):

```python
    written = 0
    first_enriched_nids = []
    nohit_nids, terms = [], []
```

Record the nid where the note is first-enriched. The existing `if first_time:` block that adds the tag (line ~144) becomes:

```python
        if first_time:
            note.add_tag(config.ENRICHED_TAG)
            first_enriched_nids.append(nid)
```

And extend the return dict (line ~160):

```python
    return {"written": written, "dropped": dropped,
            "nohit_tagged": 0 if dropped else len(nohit_nids), "terms": terms,
            "first_enriched_nids": first_enriched_nids}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run pytest tests/test_enrich.py -v`
Expected: PASS (new test + all existing enrich tests still green)

- [ ] **Step 5: Commit**

```bash
git add ankienrich/defs/enrich.py tests/test_enrich.py
git commit -m "feat(defs): enrich_notes reports first_enriched_nids"
```

---

## Task 6: Pipeline wiring — `flag_new` after enrich on apply

After the steps run, on apply, feed the defs step's first-enriched nids to `imagequeue.flag_new` and surface the count in the enrich step report.

**Files:**
- Modify: `ankienrich/pipeline.py:99-105` (the `run` function tail)
- Test: `tests/test_pipeline.py`

- [ ] **Step 1: Write the failing test**

Add to `tests/test_pipeline.py`:

```python
def test_apply_flags_new_cards_for_images(tmp_path, monkeypatch):
    from ankienrich.audio import build as audio_build
    from ankienrich.defs import build, enrich
    from ankienrich.examples import build as ex_build
    from ankienrich import imagequeue
    monkeypatch.setattr(audio_build, "ensure", lambda: "no-source")
    monkeypatch.setattr(ex_build, "ensure", lambda: "no-source")
    # defs step runs; stub enrich_notes to report two first-enriched nids.
    monkeypatch.setattr(build, "ensure", lambda: "/tmp/defs.sqlite")
    monkeypatch.setattr(pipeline, "_step_enrich",
                        lambda col, apply, idx, ov: {"written": 2, "first_enriched_nids": [11, 22]})
    seen = {}
    monkeypatch.setattr(imagequeue, "flag_new",
                        lambda col, nids: seen.setdefault("nids", list(nids)) or len(nids))
    col = _kino_col(str(tmp_path))
    try:
        res = pipeline.run(col, apply=True)
        assert seen["nids"] == [11, 22]
        assert res["steps"]["enrich"]["flagged_images"] == 2
    finally:
        col.close()


def test_dry_run_does_not_flag(tmp_path, monkeypatch):
    from ankienrich.audio import build as audio_build
    from ankienrich.defs import build
    from ankienrich.examples import build as ex_build
    from ankienrich import imagequeue
    monkeypatch.setattr(audio_build, "ensure", lambda: "no-source")
    monkeypatch.setattr(build, "ensure", lambda: "no-source")
    monkeypatch.setattr(ex_build, "ensure", lambda: "no-source")
    called = []
    monkeypatch.setattr(imagequeue, "flag_new", lambda col, nids: called.append(nids))
    col = _kino_col(str(tmp_path))
    try:
        pipeline.run(col, apply=False)
        assert called == []
    finally:
        col.close()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_pipeline.py -k "flags_new or does_not_flag" -v`
Expected: FAIL — `KeyError: 'flagged_images'` (apply test); the dry-run test fails only if wiring is wrong, so confirm both behaviours.

- [ ] **Step 3: Wire `flag_new` into `run`**

In `ankienrich/pipeline.py`, add the import at the top alongside the existing defs import:

```python
from . import imagequeue
```

Then in `run`, after the `for key, name, fn in steps:` loop and before `return out` (line ~104):

```python
    enrich_res = out["steps"].get("enrich", {})
    nids = enrich_res.get("first_enriched_nids")
    if apply and nids:
        enrich_res["flagged_images"] = imagequeue.flag_new(col, nids)
    return out
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `uv run pytest tests/test_pipeline.py -v`
Expected: PASS (new tests + existing pipeline tests still green)

- [ ] **Step 5: Commit**

```bash
git add ankienrich/pipeline.py tests/test_pipeline.py
git commit -m "feat(pipeline): flag first-enriched imageless cards for the image queue"
```

---

## Task 7: Bot — button, guard exclusion, queue conversation

Adds the `🖼 画像` button and the full queue conversation. The telebot handlers are thin glue around `imagequeue` (already unit-tested) and `mine` helpers (already unit-tested); the unit test here covers the button presence and the pure-helper reuse. Manual smoke-test instructions follow.

**Files:**
- Modify: `ankienrich/bot.py` — `home_keyboard` (line ~95), `_word` guard (line ~261), the `register`/handler block (the `_cb` callback at line ~281), and new module state + helpers.
- Test: `tests/test_bot.py`

- [ ] **Step 1: Write the failing test**

Add to `tests/test_bot.py`:

```python
from ankienrich import config


def test_home_keyboard_includes_image_button():
    kb = bot.home_keyboard()
    labels = [b["text"] for row in kb.keyboard for b in row]
    assert config.IMAGE_BUTTON in labels


def test_word_guard_excludes_image_button():
    # the miner must not treat a tap on the image button as a word to mine
    assert bot._is_word_text(config.IMAGE_BUTTON) is False
    assert bot._is_word_text(bot.RUN_BUTTON) is False
    assert bot._is_word_text("迷惑") is True
    assert bot._is_word_text("/start") is False
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `uv run pytest tests/test_bot.py -k "image_button or word_guard" -v`
Expected: FAIL — `config.IMAGE_BUTTON` missing from keyboard, and `AttributeError: module 'ankienrich.bot' has no attribute '_is_word_text'`.

- [ ] **Step 3a: Import imagequeue + add the button to the keyboard**

In `ankienrich/bot.py`, replace the existing import (line 12) — `config` is currently **not** imported, so add both it and `imagequeue`:

```python
from . import config, enrich_one, imagequeue, mine, runlock
```

In `home_keyboard` (line ~97-99), add the button on its own row:

```python
    kb.add(telebot.types.KeyboardButton(RUN_BUTTON),
           telebot.types.KeyboardButton(HELP_BUTTON))
    kb.add(telebot.types.KeyboardButton(config.IMAGE_BUTTON))
    return kb
```

- [ ] **Step 3b: Extract the word-text predicate so it can be unit-tested and reused**

Replace the inline lambda guard on `_word` (line ~261-262). First add a module-level helper near the other pure helpers (e.g. after `_inline`):

```python
def _is_word_text(text: str | None) -> bool:
    """True for a free-text word to mine — not a slash command, not a button tap."""
    return bool(text) and not text.startswith("/") and text not in (
        RUN_BUTTON, HELP_BUTTON, config.IMAGE_BUTTON)
```

Then change the `_word` handler decorator to use it:

```python
    @bot.message_handler(func=lambda m: _is_word_text(m.text))
    def _word(m):
```

- [ ] **Step 3c: Add queue module state + the OPEN handler + flow helpers**

Add module state next to `_SESSIONS` (line ~122):

```python
# Image-queue conversations, separate from the mining _SESSIONS so the two flows
# never collide. In-memory: a restart drops an in-progress queue (acceptable).
_QUEUES: dict[int, imagequeue.QueueSession] = {}
```

Inside the handler-registration block (where `_enter_image`, `_apply_mine`, etc. are defined as closures over `bot` and `chat_id`), add the queue flow:

```python
    def _show_card(chat_id_):
        q = _QUEUES.get(chat_id_)
        if q is None or q.done:
            _finish_queue(chat_id_)
            return
        card = q.current
        nxt = q.cards[q.index + 1] if q.index + 1 < len(q.cards) else None
        if nxt:
            mine.prefetch_images(nxt.term)        # warm the next card while user decides
        cands = mine.image_candidates(card.term)
        if not cands:
            # browser down / no hits: auto-skip WITHOUT tagging no-image (re-offer later)
            bot.send_message(chat_id_,
                             f"{q.index + 1}/{len(q.cards)} 「{card.term}」— 画像なし、スキップします。")
            q.advance()
            _show_card(chat_id_)
            return
        q.candidates = cands
        bot.send_message(chat_id_,
                         f"{q.index + 1}/{len(q.cards)} 「{card.term}」（{card.reading}）— {card.context}")
        numbered = mine.numbered_thumbnails(cands)
        if numbered is not None:
            media = [telebot.types.InputMediaPhoto(b) for b in numbered]
        else:
            media = [telebot.types.InputMediaPhoto(c.thumb_url, caption=str(i + 1))
                     for i, c in enumerate(cands)]
        try:
            bot.send_media_group(chat_id_, media)
        except Exception:
            pass
        bot.send_message(chat_id_, "画像を選んでください：",
                         reply_markup=_inline(imagequeue.queue_image_rows(cands)))

    def _finish_queue(chat_id_):
        q = _QUEUES.pop(chat_id_, None)
        if q is not None:
            bot.send_message(chat_id_, imagequeue.summary_text(q))

    @bot.message_handler(func=lambda m: m.text == config.IMAGE_BUTTON)
    def _image_queue(m):
        if not _authorized(m.chat.id, chat_id):
            return
        try:
            cards = imagequeue.open_queue()
        except runlock.Busy:
            bot.send_message(m.chat.id, "⏳ 実行中です — 少し後にもう一度お試しください。")
            return
        if not cards:
            bot.send_message(m.chat.id, "画像が必要な新しいカードはありません。")
            return
        _QUEUES[m.chat.id] = imagequeue.QueueSession(cards=cards)
        _show_card(m.chat.id)
```

- [ ] **Step 3d: Dispatch `q…` callbacks at the top of the existing `_cb` handler**

In `_cb` (line ~281), immediately after the `_authorized` guard and `data = c.data`, before the mining-session lookup, insert:

```python
        if data.startswith("q"):
            q = _QUEUES.get(c.message.chat.id)
            if q is None or q.done:
                _QUEUES.pop(c.message.chat.id, None)
                bot.answer_callback_query(c.id, "セッションが切れました")
                return
            if data == "qend":
                bot.answer_callback_query(c.id)
                _finish_queue(c.message.chat.id)
                return
            card = q.current
            if data == "qskip":
                try:
                    imagequeue.skip(card.nid)
                except runlock.Busy:
                    bot.answer_callback_query(c.id, "実行中です — 後でもう一度")
                    return
                q.record_skip()
                bot.answer_callback_query(c.id, "スキップしました")
                _show_card(c.message.chat.id)
                return
            if data.startswith("qpick:"):
                i = int(data.split(":")[1])
                if i >= len(q.candidates):
                    bot.answer_callback_query(c.id, "選択肢が無効になりました")
                    return
                bot.answer_callback_query(c.id, "取得中…")
                image = mine.download_image(q.candidates[i].full_url)
                if image is None:
                    bot.send_message(c.message.chat.id,
                                     "画像を取得できませんでした — もう一度選んでください。")
                    return
                try:
                    res = imagequeue.set_image(card.nid, image)
                except runlock.Busy:
                    bot.send_message(c.message.chat.id,
                                     "⏳ 実行中です — 少し後にもう一度お試しください。")
                    return
                except Exception as e:
                    bot.send_message(c.message.chat.id, f"⚠️ 同期に失敗しました: {e}")
                    q.advance()
                    _show_card(c.message.chat.id)
                    return
                if res["status"] == "gone":
                    bot.send_message(c.message.chat.id,
                                     "このカードは見つかりませんでした — スキップします。")
                    q.advance()
                else:
                    q.record_image()
                _show_card(c.message.chat.id)
                return
            return
```

- [ ] **Step 4: Run tests + full suite**

Run: `uv run pytest tests/test_bot.py -v`
Expected: PASS (button + word-guard tests, plus all existing bot tests).

Run: `uv run pytest`
Expected: PASS (whole suite green).

- [ ] **Step 5: Manual smoke test (no automated coverage for live telebot)**

With a temp/staging collection: tag one imageless Kinomoto note `ankienrich::needs-image` by hand, start the bot (`uv run anki-enrich-bot`), tap `🖼 画像`, confirm it shows `1/1 「term」…` + a thumbnail grid, pick one, and confirm the `Image` field fills and the tag clears. Tap `🖼 画像` again → "画像が必要な新しいカードはありません。". Verify tapping the button never gets mined as a word.

- [ ] **Step 6: Commit**

```bash
git add ankienrich/bot.py tests/test_bot.py
git commit -m "feat(bot): 🖼 画像 image-queue conversation"
```

---

## Task 8: Docs touch-up

Reflect the new feature in `CLAUDE.md` so the bot description stays accurate.

**Files:**
- Modify: `CLAUDE.md` (The Telegram bot section)

- [ ] **Step 1: Update the bot section**

In `CLAUDE.md`, in "## The Telegram bot", add a sentence describing the queue, e.g. after the mining description:

```markdown
Tap **🖼 画像** to walk newly-added imageless cards (those the defs pass tagged
`ankienrich::needs-image` on first enrichment) and fill each `Image` field with a
picked Bing image; skipping tags `ankienrich::no-image` (permanent). Same per-card
runlock'd pull→write→push as mining. See `ankienrich/imagequeue.py`.
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: describe the 🖼 画像 image queue"
```

---

## Self-Review Notes

- **Spec coverage:** `flag_new` (Task 2), `candidates`/`open_queue` (Tasks 2-3), `set_image`/`skip` (Task 3), `QueueSession`/`queue_image_rows`/`summary_text` (Task 4), `first_enriched_nids` (Task 5), pipeline wiring + report count (Task 6), bot button/guard/`q…` dispatch/conversation incl. auto-skip-without-tag and download-failure-stays (Task 7). The spec's "out of scope" items (back-catalog, re-imaging, batching) are intentionally not implemented.
- **Type consistency:** `Candidate(nid, term, reading, context)`, `QueueSession(cards, index, imaged, skipped, candidates)` with `done`/`current`/`advance`/`record_image`/`record_skip`; callback prefixes `qpick:`/`qskip`/`qend`; media name `q_{nid}.{ext}` — used consistently across tasks.
- **Error paths:** browser-down → `advance()` (no tag, re-offer); download failure → stay on card; note gone → "gone" status + advance; `runlock.Busy` → retry hint; push failure → reported, local write stands, advance.
