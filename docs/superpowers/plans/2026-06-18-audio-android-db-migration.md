# Term-audio android.db Migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the add-on-built term-audio store with the prebuilt `android.db` packaged export, reading it directly and ranking recordings exactly like Hoshi Reader.

**Architecture:** `var/audio.sqlite` IS the android.db file (schema `entries` + `android`, all `.mp3`, blobs embedded), placed by hand / by `deploy/push.sh`. `audio/build.py` becomes a presence check. `audio/enrich.py` ports Hoshi's `LocalAudioResolver.resolve` (reading-match-first → source priority → source-name) and reads clip bytes from the `android` table. The re-pitch pass is deleted.

**Tech Stack:** Python 3.11, `sqlite3`, `anki`, `jaconv`, `pytest`, `uv`.

**Spec:** `docs/superpowers/specs/2026-06-18-audio-android-db-migration-design.md`

**Pre-done:** The 6.2 GB android.db is already at `var/audio.sqlite` (old store preserved at `var/audio.sqlite.addon-bak`, both gitignored).

---

## File Structure

- **Modify** `ankienrich/audio/enrich.py` — new `lookup`/`read_clip`, Hoshi ranking; delete `lookup_dict` + the whole re-pitch surface and stale constants.
- **Modify** `ankienrich/audio/__init__.py` — exports follow the new surface.
- **Modify** `ankienrich/audio/build.py` — `ensure()` is presence-only; delete add-on transcode.
- **Modify** `ankienrich/config.py` — remove `LOCAL_AUDIO_ADDON_ID`.
- **Modify** `ankienrich/pipeline.py` — `_step_audio` drops the repitch calls/keys.
- **Rewrite** `tests/test_audio.py` — android schema fixtures, Hoshi-ranking tests, backfill tests; delete repitch tests.
- **Rewrite** `tests/test_audio_build.py` — `ensure()` present / no-source only.
- **Modify** docs: `CLAUDE.md`, `AGENTS.md`, `README.md`, `deploy/push.sh`, `deploy/README.md`.

> **Design note flagged for the user — exact-expression tiebreak.** Hoshi's resolver ranks by `(reading-match, source-priority, source-name)` and leaves true ties (same reading, same source — e.g. 橋/はし vs 箸/はし both in nhk16) to arbitrary DB order. This plan adds ONE extra, final tiebreak key — *prefer the row whose `expression` equals the looked-up term* — so a homograph collision deterministically returns the correct word's recording. It does not alter Hoshi's ordering on any non-tie case. Task 1 includes a test for it. If the user wants byte-for-byte Hoshi behaviour instead, drop the 4th key and the `test_exact_expression_breaks_homograph_tie` test.

---

## Task 1: Port lookup + read_clip to android.db schema and Hoshi ranking

**Files:**
- Modify: `ankienrich/audio/enrich.py`
- Modify: `ankienrich/audio/__init__.py`
- Test: `tests/test_audio.py` (full rewrite)

- [ ] **Step 1: Replace `tests/test_audio.py` with the new schema + behaviour tests**

Overwrite the whole file with:

```python
import os
import re as _re
import sqlite3

import pytest

from ankienrich import audio

anki = pytest.importorskip("anki")
from anki.collection import Collection

from ankienrich import config

_KINOMOTO_FIELDS = [
    config.TERM, config.READING, config.TERM_FURIGANA, config.SENTENCE,
    config.SENTENCE_FURIGANA, config.CLOZE_BEFORE, config.CLOZE_INSIDE,
    config.CLOZE_AFTER, config.DEFINITION, config.PITCH, config.TERM_AUDIO,
]


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


def _audio_db(path, rows, clips=()):
    """rows: (expression, reading, source, speaker, file).
    clips: (source, file, data). Mirrors the android.db store schema
    (entries has extra id/display; clip bytes live in the `android` table)."""
    con = sqlite3.connect(path)
    con.execute("CREATE TABLE entries (id integer primary key, expression text, "
                "reading text, source text, speaker text, display text, file text)")
    con.execute("CREATE TABLE android (id integer primary key, file text, "
                "source text, data blob)")
    con.executemany(
        "INSERT INTO entries (expression, reading, source, speaker, file) VALUES (?,?,?,?,?)",
        rows)
    con.executemany("INSERT INTO android (file, source, data) VALUES (?,?,?)",
                    [(f, s, d) for (s, f, d) in clips])
    con.commit()
    return con


# --- lookup ranking (Hoshi port) -----------------------------------------------

def test_lookup_prefers_nhk16_by_priority(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("橋", "はし", "nhk16", None, "audio/n.mp3"),
        ("橋", "はし", "daijisen", None, "audio/d.mp3"),
        ("橋", "はし", "shinmeikai8", None, "media/s.mp3"),
        ("橋", "はし", "jpod", None, "media/j.mp3"),
    ])
    assert audio.lookup(con, "橋", "はし") == audio.Pick("nhk16", "audio/n.mp3")


def test_daijisen_beats_shinmeikai(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("橋", "はし", "daijisen", None, "audio/d.mp3"),
        ("橋", "はし", "shinmeikai8", None, "media/s.mp3"),
    ])
    assert audio.lookup(con, "橋", "はし") == audio.Pick("daijisen", "audio/d.mp3")


def test_reading_match_ranked_first_within_source(tmp_path):
    # 角 = かど or つの; reading-match wins over the same-source expression-only row.
    con = _audio_db(str(tmp_path / "a.db"), [
        ("角", "かど", "nhk16", None, "audio/kado.mp3"),
        ("角", "つの", "nhk16", None, "audio/tsuno.mp3"),
    ])
    assert audio.lookup(con, "角", "つの") == audio.Pick("nhk16", "audio/tsuno.mp3")


def test_reading_match_beats_higher_priority_expression_only(tmp_path):
    # nhk16 has the homograph 辛い/からい (expression matches, reading does not); the
    # lower-priority jpod row reading-matches つらい and wins, because reading-match is
    # the FIRST sort key (Hoshi behaviour) — it outranks source priority.
    con = _audio_db(str(tmp_path / "a.db"), [
        ("辛い", "からい", "nhk16", None, "audio/karai.mp3"),   # expr match, reading != つらい
        ("辛い", "つらい", "jpod", None, "media/tsurai.mp3"),   # reading match
    ])
    assert audio.lookup(con, "辛い", "つらい") == audio.Pick("jpod", "media/tsurai.mp3")


def test_exact_expression_breaks_homograph_tie(tmp_path):
    # same reading + same source: prefer the row whose expression IS the term.
    con = _audio_db(str(tmp_path / "a.db"), [
        ("箸", "はし", "nhk16", None, "audio/chopsticks.mp3"),
        ("橋", "はし", "nhk16", None, "audio/bridge.mp3"),
    ])
    assert audio.lookup(con, "橋", "はし") == audio.Pick("nhk16", "audio/bridge.mp3")


def test_unsupported_extension_filtered(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("画像", "がぞう", "nhk16", None, "audio/x.aac"),   # unsupported -> dropped
        ("画像", "がぞう", "jpod", None, "media/j.mp3"),
    ])
    assert audio.lookup(con, "画像", "がぞう") == audio.Pick("jpod", "media/j.mp3")


def test_lookup_none_when_absent(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [])
    assert audio.lookup(con, "存在しない", "x") is None


def test_lookup_strips_html_and_normalizes_katakana(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("食べる", "たべる", "nhk16", None, "audio/n.mp3"),
    ])
    assert audio.lookup(con, "<b>食べる</b>", "タベル") == audio.Pick("nhk16", "audio/n.mp3")


def test_blank_reading_uses_expression_only_by_priority(tmp_path):
    # reading blank -> no reading-match boost; source priority decides.
    con = _audio_db(str(tmp_path / "a.db"), [
        ("謎", "なぞ", "nhk16", None, "audio/n.mp3"),
        ("謎", None, "forvo", "skent", "skent/n.mp3"),
    ])
    assert audio.lookup(con, "謎", "") == audio.Pick("nhk16", "audio/n.mp3")


# --- read_clip (android table) --------------------------------------------------

def test_read_clip_from_android_table(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"),
                    [("橋", "はし", "nhk16", None, "audio/n.mp3")],
                    clips=[("nhk16", "audio/n.mp3", b"MP3-nhk")])
    assert audio.read_clip(con, audio.Pick("nhk16", "audio/n.mp3")) == b"MP3-nhk"


def test_read_clip_none_when_blob_absent(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"),
                    [("橋", "はし", "nhk16", None, "audio/n.mp3")])
    assert audio.read_clip(con, audio.Pick("nhk16", "audio/n.mp3")) is None


def test_media_name_is_public():
    from ankienrich.audio import enrich
    pick = enrich.Pick("nhk16", "audio/123.mp3")
    assert enrich.media_name(pick) == "la_nhk16_123.mp3"


# --- collection-level backfill --------------------------------------------------

@pytest.fixture
def col(tmp_path):
    c = Collection(str(tmp_path / "test.anki2"))
    _new_notetype(c, config.NOTETYPE, _KINOMOTO_FIELDS)
    kino = c.models.by_name(config.NOTETYPE)
    c.models.set_current(kino)
    mined = c.decks.id("言葉文章")

    def add(term, reading, audio_val):
        n = c.new_note(kino)
        n[config.TERM] = term
        n[config.READING] = reading
        n[config.TERM_AUDIO] = audio_val
        c.add_note(n, mined)
        return n.id

    ids = {
        "empty_nhk": add("橋", "はし", ""),        # -> nhk16
        "empty_forvo": add("筋トレ", "きんとれ", ""),  # -> forvo
        "empty_none": add("造語", "ぞうご", ""),     # -> nothing
        "filled": add("猫", "ねこ", "[sound:cat.mp3]"),  # skip (has audio)
    }
    yield c, ids
    c.close()


@pytest.fixture
def laudio(tmp_path):
    rows = [
        ("橋", "はし", "nhk16", None, "audio/n.mp3"),
        ("筋トレ", None, "forvo", "strawberrybrown", "sb/b.mp3"),
    ]
    clips = [
        ("nhk16", "audio/n.mp3", b"MP3-nhk"),
        ("forvo", "sb/b.mp3", b"MP3-forvo"),
    ]
    return _audio_db(str(tmp_path / "a.sqlite"), rows, clips)


def test_select_targets_only_empty(col, laudio):
    c, ids = col
    targets = set(audio.select_targets(c))
    assert ids["empty_nhk"] in targets
    assert ids["empty_forvo"] in targets
    assert ids["empty_none"] in targets
    assert ids["filled"] not in targets


def test_plan_backfill_classifies(col, laudio):
    c, ids = col
    plan = audio.plan_backfill(c, laudio)
    assert plan["by_source"] == {"nhk16": 1, "forvo": 1}
    assert plan["filled"] == 2
    assert plan["nothing"] == 1
    assert plan["missing_file"] == 0


def test_run_backfill_writes_sound_and_copies_media(col, laudio):
    c, ids = col
    res = audio.run_backfill(c, laudio)
    assert res["filled"] == {"nhk16": 1, "forvo": 1}
    n = c.get_note(ids["empty_nhk"])
    assert n[config.TERM_AUDIO].startswith("[sound:") and n[config.TERM_AUDIO].endswith(".mp3]")
    fname = _re.search(r"\[sound:([^\]]+)\]", n[config.TERM_AUDIO]).group(1)
    assert c.media.have(fname)
    with open(os.path.join(c.media.dir(), fname), "rb") as fh:
        assert fh.read() == b"MP3-nhk"
    assert c.get_note(ids["empty_none"])[config.TERM_AUDIO] == ""
    assert c.get_note(ids["filled"])[config.TERM_AUDIO] == "[sound:cat.mp3]"


def test_run_backfill_is_rerun_safe(col, laudio):
    c, ids = col
    audio.run_backfill(c, laudio)
    assert audio.run_backfill(c, laudio)["filled"] == {}


def test_run_backfill_one_bad_card_does_not_strand_batch(col, laudio, monkeypatch):
    c, ids = col
    real_write = c.media.write_data
    calls = {"n": 0}

    def flaky_write(name, data):
        calls["n"] += 1
        if calls["n"] == 1:
            raise RuntimeError("simulated media failure")
        return real_write(name, data)

    monkeypatch.setattr(c.media, "write_data", flaky_write)
    res = audio.run_backfill(c, laudio)
    assert len(res["errors"]) == 1
    assert sum(res["filled"].values()) == 1
```

- [ ] **Step 2: Run the new tests, verify they fail**

Run: `uv run pytest tests/test_audio.py -q`
Expected: FAIL — `lookup`/`read_clip` still query the old `clips` table / old ranking; `AttributeError` or assertion failures. (The DB now has an `android` table, not `clips`.)

- [ ] **Step 3: Rewrite the lookup core in `ankienrich/audio/enrich.py`**

Replace the module docstring and everything from the constants down through `media_name` (i.e. lines 1–116, ending at the `media_name` function) with:

```python
"""Term-audio backfill from the prebuilt android.db store (var/audio.sqlite).

The store is the local-audio-yomichan / AnkiConnect-Android packaged export:
    entries(id, expression, reading, source, speaker, display, file)  -- lookup index
    android(id, file, source, data BLOB)                              -- clip bytes
all clips .mp3, blobs embedded. We open it read-only.

`lookup` is a direct port of Hoshi Reader's LocalAudioResolver.resolve: gather every
row matching the term or the (kata->hira) reading, drop unsupported audio, then rank
reading-matches first, then by source priority, then source name. (We add one final
tiebreak — exact-expression — so homograph collisions in the same source resolve to
the right word; Hoshi leaves that to DB order.)

Fills empty `Term Audio` on in-scope Kinomoto notes. No `aqt`, no add-on coupling.
"""
import os
import re
from collections import Counter
from dataclasses import dataclass

import jaconv

from .. import config

# Source preference, highest first (Hoshi Reader's BuiltInPriority). Sources absent
# from this list sort last, then alphabetically.
SOURCE_PRIORITY = ["nhk16", "daijisen", "shinmeikai8", "jpod", "jpod_alternate",
                   "taas", "ozk5", "forvo", "forvo_ext", "forvo_ext2"]
SUPPORTED_EXT = {"mp3", "opus", "ogg"}

_TAG = re.compile(r"<[^>]+>")
_SOUND = re.compile(r"\[sound:[^\]]+\]")


def _clean(s: str) -> str:
    return _TAG.sub("", s or "").replace("​", "").replace("&nbsp;", "").strip()


def _to_hira(s: str) -> str:
    return jaconv.kata2hira(s)


def _ext(file: str) -> str:
    return file.rsplit(".", 1)[-1].lower() if "." in file else ""


def _source_rank(source: str) -> int:
    try:
        return SOURCE_PRIORITY.index(source)
    except ValueError:
        return len(SOURCE_PRIORITY)


@dataclass
class Pick:
    """A chosen recording. `file` is the source-relative filename (the key into the
    `android` table, and—via `media_name`—the media file basename)."""
    source: str
    file: str
    speaker: str | None = None


def lookup(conn, term: str, reading: str) -> Pick | None:
    """Hoshi-port lookup against the android.db store. Returns the best Pick or None.

    Gathers rows where expression == term OR reading == (kata->hira) reading, drops
    unsupported extensions, then takes the row ranked first by:
      1. reading-match (rows whose reading equals the query reading come first),
      2. source priority (SOURCE_PRIORITY),
      3. source name,
      4. exact-expression (the row whose expression IS the term wins same-reading
         same-source ties).
    """
    term = _clean(term)
    reading = _to_hira(_clean(reading))
    if not term:
        return None
    if reading:
        rows = conn.execute(
            "SELECT source, expression, reading, file FROM entries "
            "WHERE expression=? OR reading=?", (term, reading)).fetchall()
    else:
        rows = conn.execute(
            "SELECT source, expression, reading, file FROM entries "
            "WHERE expression=?", (term,)).fetchall()
    cands = [r for r in rows if _ext(r[3]) in SUPPORTED_EXT]
    if not cands:
        return None

    def _key(r):
        src, expr, rd, _file = r
        return (
            0 if (reading and rd == reading) else 1,
            _source_rank(src),
            src,
            0 if expr == term else 1,
        )

    src, _expr, _rd, file = min(cands, key=_key)
    return Pick(src, file)


def read_clip(conn, pick: Pick) -> bytes | None:
    """The picked recording's bytes from the `android` table, or None if absent."""
    row = conn.execute(
        "SELECT data FROM android WHERE source=? AND file=? LIMIT 1",
        (pick.source, pick.file),
    ).fetchone()
    return row[0] if row else None


def media_name(pick: Pick) -> str:
    """Deterministic media filename for a pick. basenames can collide across clips;
    Anki's write_data renames on content conflict, so each card still gets its bytes."""
    return f"la_{pick.source}_{os.path.basename(pick.file)}"
```

- [ ] **Step 4: Delete the re-pitch surface from `ankienrich/audio/enrich.py`**

Remove these now-obsolete definitions entirely (they follow `run_backfill`):
`_current_sound`, `_current_sound_file`, `select_repitch_targets`, `plan_repitch`,
`run_repitch`, and the `# --- re-pitch pass …` section comment.

Leave `_search`, `select_targets`, `plan_backfill`, `run_backfill` unchanged — they
already call the new `lookup`/`read_clip`. (`plan_backfill` still references
`config.SAMPLE_N` and `Counter`, both still imported.)

- [ ] **Step 5: Update `ankienrich/audio/__init__.py`**

Replace the file with:

```python
"""Term-audio enrichment package. Runtime lives in `enrich`; the prebuilt store is
located by `build` and opened by `index`."""
from .enrich import (
    SOURCE_PRIORITY, SUPPORTED_EXT, Pick,
    lookup, read_clip, media_name,
    plan_backfill, run_backfill, select_targets,
)

__all__ = [
    "SOURCE_PRIORITY", "SUPPORTED_EXT", "Pick",
    "lookup", "read_clip", "media_name",
    "plan_backfill", "run_backfill", "select_targets",
]
```

- [ ] **Step 6: Run the tests, verify they pass**

Run: `uv run pytest tests/test_audio.py -q`
Expected: PASS (all tests green).

- [ ] **Step 7: Commit**

```bash
git add ankienrich/audio/enrich.py ankienrich/audio/__init__.py tests/test_audio.py
git commit -m "feat(audio): port term-audio lookup to android.db + Hoshi ranking

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Make `build.ensure()` a presence check; drop the add-on transcode

**Files:**
- Modify: `ankienrich/audio/build.py` (full rewrite)
- Modify: `ankienrich/config.py` (remove `LOCAL_AUDIO_ADDON_ID`)
- Test: `tests/test_audio_build.py` (full rewrite)

- [ ] **Step 1: Replace `tests/test_audio_build.py`**

Overwrite the whole file with:

```python
from ankienrich.audio import build


def test_ensure_present_when_store_exists(tmp_path):
    db = tmp_path / "audio.sqlite"
    db.write_text("")  # any existing file
    assert build.ensure(db_path=str(db)) == "present"


def test_ensure_no_source_when_missing(tmp_path):
    db = tmp_path / "audio.sqlite"
    assert build.ensure(db_path=str(db)) == "no-source"
    assert not db.exists()
```

- [ ] **Step 2: Run, verify it fails**

Run: `uv run pytest tests/test_audio_build.py -q`
Expected: FAIL — current `build.ensure` requires `addons_dir` handling / `locate_addon`; signature differs.

- [ ] **Step 3: Replace `ankienrich/audio/build.py`**

Overwrite the whole file with:

```python
"""Locate the prebuilt term-audio store (var/audio.sqlite).

The store is the android.db packaged export (local-audio-yomichan /
AnkiConnect-Android format: `entries` + `android` tables, blobs embedded). It is
placed at var/audio.sqlite by hand (locally) or by deploy/push.sh (server) — there
is nothing to build at runtime. Runtime-read-only."""
import os

from .. import paths


def ensure(db_path=None) -> str:
    """'present' if the store exists, else 'no-source'. Never builds."""
    db_path = db_path or paths.AUDIO_DB
    return "present" if os.path.exists(db_path) else "no-source"
```

- [ ] **Step 4: Remove `LOCAL_AUDIO_ADDON_ID` from `ankienrich/config.py`**

Delete the line:

```python
LOCAL_AUDIO_ADDON_ID = "1045800357"
```

(Verify nothing else references it: `rg -n LOCAL_AUDIO_ADDON_ID ankienrich` returns nothing.)

- [ ] **Step 5: Run the build tests, verify they pass**

Run: `uv run pytest tests/test_audio_build.py -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add ankienrich/audio/build.py ankienrich/config.py tests/test_audio_build.py
git commit -m "feat(audio): build.ensure() is a presence check; drop add-on transcode

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Drop the re-pitch step from the pipeline

**Files:**
- Modify: `ankienrich/pipeline.py` (`_step_audio`, lines ~22–39)
- Test: `tests/test_pipeline.py` (no change needed — see Step 3)

- [ ] **Step 1: Rewrite `_step_audio` in `ankienrich/pipeline.py`**

Replace the existing `_step_audio` function with:

```python
def _step_audio(col, apply: bool) -> dict:
    state = audio_build.ensure()
    if state == "no-source":
        return {"skipped": "audio.sqlite missing — place the android.db store at var/audio.sqlite"}
    conn = audio_index.open_index()
    try:
        if apply:
            res = audio.run_backfill(col, conn)
            return {"filled": dict(res["filled"]), "errors": len(res.get("errors", []))}
        plan = audio.plan_backfill(col, conn)
        return {"will_fill": plan["filled"], "by_source": dict(plan["by_source"]),
                "none": plan["nothing"], "missing_file": plan["missing_file"]}
    finally:
        conn.close()
```

- [ ] **Step 2: Verify no other code references the removed audio symbols**

Run: `rg -n "run_repitch|plan_repitch|select_repitch|lookup_dict|FILL_ORDER|SOURCE_LABELS|FORVO_SPEAKER|DICT_SOURCES" ankienrich tests`
Expected: no matches (poc/ is excluded from these dirs; it is reference-only and untouched).

- [ ] **Step 3: Run the pipeline tests, verify they pass**

`tests/test_pipeline.py` stubs `audio_build.ensure` to `"no-source"`, so `_step_audio` returns early and never touches the changed keys — no test edit needed.

Run: `uv run pytest tests/test_pipeline.py -q`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add ankienrich/pipeline.py
git commit -m "feat(audio): drop re-pitch step from enrichment pipeline

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Update docs and deploy notes

**Files:**
- Modify: `CLAUDE.md`, `AGENTS.md`, `README.md`, `deploy/push.sh`, `deploy/README.md`

- [ ] **Step 1: `CLAUDE.md` — term-audio paragraph**

Find the paragraph beginning "Term-audio enrichment builds `var/audio.sqlite` from the `local-audio-yomichan`…" and replace it with:

```
- Term audio comes from a prebuilt store at `var/audio.sqlite` — the android.db
  packaged export (local-audio-yomichan / AnkiConnect-Android format: `entries` +
  `android` tables, blobs embedded). It is placed there directly (no build step);
  the runtime opens it read-only. Lookup is a port of Hoshi Reader's resolver:
  reading-match first, then source priority
  (nhk16→daijisen→shinmeikai8→jpod→…→forvo_ext2). Absent the store the step is
  skipped, not failed.
```

- [ ] **Step 2: `AGENTS.md` — mirror the same change**

Find the corresponding term-audio bullet (around line 38, "Term-audio enrichment builds `var/audio.sqlite` from the `local-audio-yomichan`…") and replace it with the same paragraph as Step 1.

- [ ] **Step 3: `README.md` — fix the audio-source line**

Find the line referencing term audio "(sourced from the `local-audio-yomichan` add-on)" (around line 17) and change it to read "(the prebuilt android.db term-audio store at `var/audio.sqlite`)".

- [ ] **Step 4: `deploy/push.sh` — refresh the artifact-size aside**

Replace the comment block at the "Push build artifacts" step:

```bash
# --- Push build artifacts (always; rsync delta makes reruns cheap) ----------
say "Syncing sqlite build artifacts"
```

with:

```bash
# --- Push build artifacts (always; rsync delta makes reruns cheap) ----------
# audio.sqlite is the ~6.2 GB android.db term-audio store: the FIRST push is a
# large one-time transfer; rsync delta keeps later reruns near-free.
say "Syncing sqlite build artifacts"
```

(The `artifacts=(… var/audio.sqlite …)` rsync itself is unchanged — it already ships the store.)

- [ ] **Step 5: `deploy/README.md` — sizes + provenance**

- Line ~5: change "~1.4 GB of prebuilt sqlite" to "~6.5 GB of prebuilt sqlite (the term-audio store alone is ~6.2 GB)".
- Step 3 in the numbered list (~line 26-27): change the audio aside from "~2 GB but rarely changes" to "~6.2 GB; the first push is a large one-time transfer, reruns stay cheap via rsync delta".
- The term-audio paragraph (~lines 37-41): replace with:

```
Term audio ships as the prebuilt `audio.sqlite` artifact — the android.db packaged
export (local-audio-yomichan / AnkiConnect-Android format), placed at
`var/audio.sqlite` directly with no build step. `push.sh`'s preflight requires the
file, so the box always ships with audio; the runtime self-skips a missing store
rather than failing.
```

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md AGENTS.md README.md deploy/push.sh deploy/README.md
git commit -m "docs: term audio is the prebuilt android.db store, not the add-on

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Full-suite + real-store verification

**Files:** none (verification only)

- [ ] **Step 1: Run the whole test suite**

Run: `uv run pytest -q`
Expected: PASS (no failures, no errors). If `tests/test_mine.py` / `tests/test_enrich_one.py` reference `audio_enrich.Pick`, they still pass — `Pick` is unchanged.

- [ ] **Step 2: Smoke-test lookup against the real 6.2 GB store**

Run:

```bash
uv run python -c "
from ankienrich.audio import index, enrich
c = index.open_index()
for term, reading in [('橋','はし'), ('猫','ねこ'), ('食べる','たべる')]:
    p = enrich.lookup(c, term, reading)
    print(term, reading, '->', p, 'clip bytes:', len(enrich.read_clip(c, p) or b'') if p else None)
"
```

Expected: each term resolves to a Pick (likely `nhk16`/`daijisen`/`shinmeikai8`) with non-zero clip bytes — proving the new schema + ranking work against the actual store.

- [ ] **Step 3: Confirm the build step reports present**

Run: `uv run python -c "from ankienrich.audio import build; print(build.ensure())"`
Expected: `present`.

- [ ] **Step 4: Final commit if anything was touched**

If Steps 1-3 surfaced a fix, commit it. Otherwise nothing to do — migration complete. Remind the user they can delete `var/audio.sqlite.addon-bak` once satisfied.
```
