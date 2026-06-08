# Anki Align Rebuild — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a clean `ankialign` CLI that pulls the collection from AnkiWeb, runs the enrichment suite (audio, pitch, JRP sentence markup, definitions, cloze) + dedupe + a repo-owned card template, and pushes back — porting the proven logic from `poc/` while dropping its migration cruft.

**Architecture:** Single Python package run via `uv run anki-align`. The tool keeps its **own** collection working copy (never the desktop profile); it syncs down at the start of every run, computes a dry-run plan, and only writes + uploads under `--apply`. Enrichment passes are pure-where-possible modules orchestrated by `pipeline.py`; each pass only touches cards missing the relevant field. The **card design is a first-class repo citizen** under `card/kinomoto/` (front/back/style), applied wholesale by the template pass.

**Tech Stack:** Python ≥3.11, `uv`; `anki` library (collection I/O + headless AnkiWeb sync); `sudachipy` + `sudachidict-full` (JRP tokenization); `pytest`.

**Porting rule:** `poc/` is reference-only — **never import from or depend on it at runtime**. Porting = copy the file/data into the new tree, then apply the edits listed in the task. The poc stays untouched.

**Terminology rule:** Do not use the word "gold" anywhere in code, comments, or test names. Use "reference" (for an authoritative expected value) or "observed" (for a value read back from the existing collection).

**Comment discipline:** Keep docstrings and comments terse. This repo's durable design rationale lives in code, not in a separate spec — so when porting, carry over the *load-bearing* "why" (e.g. the stoplist/merge reasoning in `tokenize.py`, the conjugation-rule derivations) but **drop decorative or historical comments**: poc migration notes, "self-heal"/"old patch" explanations, "(user call)" asides, goal-number references, and anything narrating the poc's history. One tight sentence of rationale beats a paragraph. Don't over-document.

**Doc lifecycle:** This plan and `prd-sketch.md` are throwaway scaffolding. Task 12.4 deletes them once the suite is green. The only durable docs are `PRD.md`, `AGENTS.md`/`CLAUDE.md`, and the code itself.

---

## File Structure

```
pyproject.toml                     # uv project, console script, deps, pytest config
.gitignore                         # add var/, dicts/, collection working copy
card/kinomoto/
    front.html                     # canonical card front (qfmt) — first-class
    back.html                      # canonical card back (afmt)
    style.css                      # canonical card CSS (authored design, no generated dict styles)
ankialign/
    __init__.py
    config.py                      # notetype name, field-name map, deck excludes, env creds, paths
    paths.py                       # repo-root / var / dicts / data path resolution
    collection.py                  # open working copy, in-scope search, verified backup
    markup.py                      # jrp surface/reading/to_furigana (pure subset)
    pitch.py                       # accent-index lookup -> SVG backfill
    pitch_svg.py                   # pure SVG pitch-graph generator
    cloze.py                       # split Sentence into Cloze Before/Inside/After
    audio.py                       # term-audio fill chain + jpod->dict re-pitch
    dedupe.py                      # merge duplicate notes, reassign revlog
    template.py                    # apply card/kinomoto/* + generated dict styles to notetype
    sync.py                        # headless AnkiWeb pull/push
    pipeline.py                    # orchestrate the enrichment suite
    cli.py                         # argparse entry point + dry-run rendering
    data/
        accent_index.marshal.gz    # shipped accent index (small, committed)
    jrp_fill/                      # JRP sentence-markup engine
        __init__.py                # make_generator()
        kana.py                    # KANJI_RE + hira() (was _utils.py)
        unit.py
        accent.py
        tokenize.py
        conjugate.py
        align.py
        emit.py
        splice.py
        engine.py
    defs/                          # definition enrichment
        __init__.py
        render.py                  # Yomitan structured-content -> HTML, choose_jj, glosses, assemble
        index.py                   # open + lookup defs.sqlite
        enrich.py                  # note rewriting (Meaning/Original Meaning/Definition Source)
        build.py                   # build defs.sqlite + dict_styles.css from Yomitan dicts
tools/
    build_accent_index.py          # (optional) rebuild the accent index
var/                               # gitignored: built defs.sqlite, dict_styles.css, working collection
dicts/                             # gitignored: source Yomitan dict folders (sankoku8, koujien7, jitsuyo, daijisen, jmdict)
tests/
    ... (ported + new, see phases)
```

**Field-name map (logical role → real Kinomoto field), centralized in `config.py`:**

| role | field |
|------|-------|
| term | `Term` |
| reading | `Reading` |
| term_furigana | `Furigana` |
| sentence | `Sentence` |
| sentence_furigana | `SentenceFurigana` |
| term_audio | `Term Audio` |
| definition | `Meaning` |
| pitch | `Pitch Accent` |
| cloze_before | `Cloze Before` |
| cloze_inside | `Cloze Inside` |
| cloze_after | `Cloze After` |
| original_meaning | `Original Meaning` (added by enrich) |
| definition_source | `Definition Source` (added by enrich) |

---

## Phase 0 — Scaffold & data assets

### Task 0.1: Project skeleton

**Files:**
- Create: `pyproject.toml`, `ankialign/__init__.py`, `tests/__init__.py`
- Modify: `.gitignore`

- [ ] **Step 1: Write `pyproject.toml`**

```toml
[project]
name = "ankialign"
version = "0.1.0"
requires-python = ">=3.11"

[project.scripts]
anki-align = "ankialign.cli:main"

[dependency-groups]
dev = ["pytest>=8", "anki>=24.11"]
jrp = ["sudachipy>=0.6.8", "sudachidict-full>=20240409"]

[tool.uv]
default-groups = ["dev", "jrp"]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["ankialign"]

[tool.pytest.ini_options]
testpaths = ["tests"]
```

- [ ] **Step 2: Empty package markers**

Create `ankialign/__init__.py` and `tests/__init__.py` as empty files.

- [ ] **Step 3: Extend `.gitignore`**

Append:

```
# Built artifacts and source dicts (large, local-only)
var/
dicts/
```

- [ ] **Step 4: Verify uv resolves**

Run: `uv sync`
Expected: resolves and installs `anki`, `sudachipy`, `sudachidict-full`, `pytest` with no error.

- [ ] **Step 5: Commit**

```bash
git add pyproject.toml ankialign/__init__.py tests/__init__.py .gitignore
git commit -m "chore: scaffold ankialign package"
```

### Task 0.2: Copy committed data assets

**Files:**
- Create: `ankialign/data/accent_index.marshal.gz`

- [ ] **Step 1: Copy the accent index**

```bash
mkdir -p ankialign/data
cp poc/ankialign/data/accent_index.marshal.gz ankialign/data/
```

- [ ] **Step 2: Verify it loads (after Task 2.1 lands the loader, this is a smoke check)**

Defer verification to Task 2.1's test. For now just confirm the file copied:
Run: `ls -la ankialign/data/accent_index.marshal.gz`
Expected: ~3.4 MB file present.

- [ ] **Step 3: Commit**

```bash
git add ankialign/data/accent_index.marshal.gz
git commit -m "chore: ship prebuilt accent index"
```

### Task 0.3: Stage source dicts for the def-index build

**Files:**
- Create (gitignored): `dicts/{sankoku8,koujien7,jitsuyo,daijisen,jmdict}/`

The definition index is **rebuilt from sources** (user decision). The runtime needs only the 5 dicts in the J-J chain + JMdict; the other poc dicts are not used.

- [ ] **Step 1: Copy the 5 needed Yomitan dict folders**

```bash
mkdir -p dicts
for d in sankoku8 koujien7 jitsuyo daijisen jmdict; do
  cp -r "poc/research/dict-compare/dicts/$d" "dicts/$d"
done
```

- [ ] **Step 2: Verify term banks present**

Run: `ls dicts/sankoku8/term_bank_*.json | head` and `ls dicts/jmdict/term_bank_*.json | head`
Expected: each lists `term_bank_*.json` files.

- [ ] **Step 3: No commit** (gitignored). Note the dirs exist locally for the build in Phase 6.

### Task 0.4: Extract the canonical card design into the repo (first-class template)

**Files:**
- Create: `card/kinomoto/front.html`, `card/kinomoto/back.html`, `card/kinomoto/style.css`

The current live template already reflects the latest poc-applied state, so extracting it as-is yields the correct canonical design. Strip only the **generated** dict-styles block from CSS (that is appended at apply-time from `var/dict_styles.css`).

- [ ] **Step 1: Dump the template from the existing collection**

```bash
uv run python - <<'PY'
import os
from anki.collection import Collection
c = Collection("poc/collection.anki2")
try:
    m = c.models.by_name("jidoujisho Kinomoto")
    t = m["tmpls"][0]
    os.makedirs("card/kinomoto", exist_ok=True)
    with open("card/kinomoto/front.html", "w", encoding="utf-8") as f:
        f.write(t["qfmt"])
    with open("card/kinomoto/back.html", "w", encoding="utf-8") as f:
        f.write(t["afmt"])
    # authored design = everything before the generated dict-styles marker
    css = m["css"].split("/* ---- AnkiAlign dict styles", 1)[0].rstrip() + "\n"
    with open("card/kinomoto/style.css", "w", encoding="utf-8") as f:
        f.write(css)
finally:
    c.close()
PY
```

- [ ] **Step 2: Verify the three files are non-empty and CSS has no generated dict-styles block**

Run: `wc -l card/kinomoto/* && grep -c "AnkiAlign dict styles" card/kinomoto/style.css`
Expected: three non-empty files; grep count `0`.

- [ ] **Step 3: Commit**

```bash
git add card/kinomoto/front.html card/kinomoto/back.html card/kinomoto/style.css
git commit -m "feat: vendor canonical Kinomoto card design as first-class repo files"
```

### Task 0.5: `paths.py` and `config.py`

**Files:**
- Create: `ankialign/paths.py`, `ankialign/config.py`
- Test: `tests/test_config.py`

- [ ] **Step 1: Write `ankialign/paths.py`**

```python
"""Filesystem locations. Repo-root relative so `uv run` from the project works."""
import os

_PKG = os.path.dirname(__file__)
ROOT = os.path.dirname(_PKG)

DATA_DIR = os.path.join(_PKG, "data")
ACCENT_INDEX = os.path.join(DATA_DIR, "accent_index.marshal.gz")

VAR_DIR = os.path.join(ROOT, "var")
DEFS_DB = os.path.join(VAR_DIR, "defs.sqlite")
DICT_STYLES = os.path.join(VAR_DIR, "dict_styles.css")
WORKING_COLLECTION = os.path.join(VAR_DIR, "collection.anki2")

DICTS_DIR = os.path.join(ROOT, "dicts")
CARD_DIR = os.path.join(ROOT, "card", "kinomoto")


def ensure_var() -> str:
    os.makedirs(VAR_DIR, exist_ok=True)
    return VAR_DIR
```

- [ ] **Step 2: Write `ankialign/config.py`**

```python
"""Static configuration for this single-user tool: target note type, the
logical-role -> field-name map, in-scope deck filter, and AnkiWeb credentials."""
import os

NOTETYPE = "jidoujisho Kinomoto"

# Logical role -> real Kinomoto field name. The one place to retarget fields.
TERM = "Term"
READING = "Reading"
TERM_FURIGANA = "Furigana"
SENTENCE = "Sentence"
SENTENCE_FURIGANA = "SentenceFurigana"
TERM_AUDIO = "Term Audio"
DEFINITION = "Meaning"
PITCH = "Pitch Accent"
CLOZE_BEFORE = "Cloze Before"
CLOZE_INSIDE = "Cloze Inside"
CLOZE_AFTER = "Cloze After"
ORIGINAL_MEANING = "Original Meaning"
DEFINITION_SOURCE = "Definition Source"

# A note is in scope unless one of its decks' names contains one of these.
EXCLUDE_DECK_SUBSTRINGS = ["Core2.3k", "文法"]

# Definition enrichment.
JJ_CHAIN = ["sankoku8", "koujien7", "jitsuyo", "daijisen"]  # first hit wins
JE_DICT = "jmdict"
GLOSS_CAP = 4
DICT_LABELS = {
    "sankoku8": "三省堂", "koujien7": "広辞苑", "jitsuyo": "実用",
    "daijisen": "大辞泉", "jmdict": "JMdict",
}
ENRICHED_TAG = "ankialign::enriched"
NO_DEF_TAG = "ankialign::no-definition"
NO_HIT_DROP_THRESHOLD = 20

# local-audio-yomichan add-on (AnkiWeb code) — source of term-audio backfill.
LOCAL_AUDIO_ADDON_ID = "1045800357"

# How many before/after samples to show in dry-run output.
SAMPLE_N = 5


def in_scope(deck_name: str) -> bool:
    return not any(sub in deck_name for sub in EXCLUDE_DECK_SUBSTRINGS)


def deck_exclusion_search() -> str:
    """Anki search clause excluding out-of-scope decks."""
    return " ".join(f"-deck:*{sub}*" for sub in EXCLUDE_DECK_SUBSTRINGS)


def ankiweb_credentials() -> tuple[str, str]:
    """(username, password) from env. Raises if unset."""
    user = os.environ.get("ANKIWEB_USERNAME")
    pw = os.environ.get("ANKIWEB_PASSWORD")
    if not user or not pw:
        raise RuntimeError(
            "set ANKIWEB_USERNAME and ANKIWEB_PASSWORD in the environment")
    return user, pw
```

- [ ] **Step 3: Write `tests/test_config.py`**

```python
import pytest
from ankialign import config


def test_in_scope_excludes_core_and_grammar():
    assert config.in_scope("Mining::Anime")
    assert not config.in_scope("Core2.3k::Vocab")
    assert not config.in_scope("文法")


def test_deck_exclusion_search():
    s = config.deck_exclusion_search()
    assert "-deck:*Core2.3k*" in s
    assert "-deck:*文法*" in s


def test_credentials_require_env(monkeypatch):
    monkeypatch.delenv("ANKIWEB_USERNAME", raising=False)
    monkeypatch.delenv("ANKIWEB_PASSWORD", raising=False)
    with pytest.raises(RuntimeError):
        config.ankiweb_credentials()
    monkeypatch.setenv("ANKIWEB_USERNAME", "u")
    monkeypatch.setenv("ANKIWEB_PASSWORD", "p")
    assert config.ankiweb_credentials() == ("u", "p")
```

- [ ] **Step 4: Run tests**

Run: `uv run pytest tests/test_config.py -v`
Expected: 3 passed.

- [ ] **Step 5: Commit**

```bash
git add ankialign/paths.py ankialign/config.py tests/test_config.py
git commit -m "feat: config (field map, deck filter, AnkiWeb creds) + paths"
```

---

## Phase 1 — Pure string/markup foundation

These modules have no Anki/Sudachi dependency. Port verbatim except where noted.

### Task 1.1: `markup.py` (pure subset)

**Files:**
- Create: `ankialign/markup.py`
- Test: `tests/test_markup.py`

- [ ] **Step 1: Port the needed functions**

Copy `poc/ankialign/markup.py` to `ankialign/markup.py`, then **delete** `entryfurigana_surface`, `entryfurigana_reading`, `as_sound`, `as_image` and the `_AUDIO_EXT` / `_IMG_EXT` regexes (only the dropped note-conversion path used them). Keep `_RUBY`, `_HAS_KANJI`, `_anki_surface`, `_anki_reading`, `jrp_to_furigana`, `jrp_surface`, `jrp_reading`.

- [ ] **Step 2: Port its tests**

Copy `poc/tests/test_markup.py` to `tests/test_markup.py`, then remove any test referencing the deleted functions (`as_sound`/`as_image`/`entryfurigana_*`).

- [ ] **Step 3: Run**

Run: `uv run pytest tests/test_markup.py -v`
Expected: all pass.

- [ ] **Step 4: Commit**

```bash
git add ankialign/markup.py tests/test_markup.py
git commit -m "feat: port pure JRP/furigana markup helpers"
```

### Task 1.2: `jrp_fill/kana.py`, `unit.py` (pure)

**Files:**
- Create: `ankialign/jrp_fill/__init__.py` (temporary stub), `ankialign/jrp_fill/kana.py`, `ankialign/jrp_fill/unit.py`
- Test: `tests/test_jrp_unit.py`

- [ ] **Step 1: Stub the package init**

Create `ankialign/jrp_fill/__init__.py` containing only a module docstring for now (the `make_generator` body lands in Task 2.3).

- [ ] **Step 2: Port `kana.py`**

Copy `poc/ankialign/jrp_fill/_utils.py` to `ankialign/jrp_fill/kana.py` verbatim (exports `KANJI_RE`, `hira`). Update its docstring's filename reference if present.

- [ ] **Step 3: Port `unit.py`**

Copy `poc/ankialign/jrp_fill/unit.py` to `ankialign/jrp_fill/unit.py` verbatim.

- [ ] **Step 4: Port the test**

Copy `poc/tests/test_jrp_unit.py` to `tests/test_jrp_unit.py`.

- [ ] **Step 5: Run**

Run: `uv run pytest tests/test_jrp_unit.py -v`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add ankialign/jrp_fill/__init__.py ankialign/jrp_fill/kana.py ankialign/jrp_fill/unit.py tests/test_jrp_unit.py
git commit -m "feat: port jrp kana helpers + Unit"
```

### Task 1.3: `jrp_fill/align.py`, `emit.py` (pure)

**Files:**
- Create: `ankialign/jrp_fill/align.py`, `ankialign/jrp_fill/emit.py`
- Test: `tests/test_jrp_align.py`, `tests/test_jrp_emit.py`

- [ ] **Step 1: Port `align.py`**

Copy `poc/ankialign/jrp_fill/align.py`, then change the import `from ._utils import KANJI_RE, hira` → `from .kana import KANJI_RE, hira`.

- [ ] **Step 2: Port `emit.py`**

Copy `poc/ankialign/jrp_fill/emit.py` verbatim (its imports `.unit`, `.align` are unchanged).

- [ ] **Step 3: Port tests**

Copy `poc/tests/test_jrp_align.py` and `poc/tests/test_jrp_emit.py` verbatim.

- [ ] **Step 4: Run**

Run: `uv run pytest tests/test_jrp_align.py tests/test_jrp_emit.py -v`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add ankialign/jrp_fill/align.py ankialign/jrp_fill/emit.py tests/test_jrp_align.py tests/test_jrp_emit.py
git commit -m "feat: port jrp furigana alignment + emit"
```

### Task 1.4: `jrp_fill/conjugate.py` (pure)

**Files:**
- Create: `ankialign/jrp_fill/conjugate.py`
- Test: `tests/test_jrp_conjugate.py`, fixture `tests/data/conj_accent_cases.tsv`

- [ ] **Step 1: Port `conjugate.py`**

Copy `poc/ankialign/jrp_fill/conjugate.py` verbatim, then in its docstring remove the words "ground truth"/"gold" — replace the parenthetical "(tests/data/conj_accent_cases.tsv)" reference's surrounding phrasing with "reference cases in tests/data/conj_accent_cases.tsv".

- [ ] **Step 2: Copy the fixture**

```bash
mkdir -p tests/data
cp poc/tests/data/conj_accent_cases.tsv tests/data/
```

- [ ] **Step 3: Port the test**

Copy `poc/tests/test_jrp_conjugate.py`; if it references the term "gold" in any variable/name, rename to "reference".

- [ ] **Step 4: Run**

Run: `uv run pytest tests/test_jrp_conjugate.py -v`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add ankialign/jrp_fill/conjugate.py tests/test_jrp_conjugate.py tests/data/conj_accent_cases.tsv
git commit -m "feat: port conjugated-pitch rules + reference cases"
```

### Task 1.5: `pitch_svg.py` (pure)

**Files:**
- Create: `ankialign/pitch_svg.py`
- Test: `tests/test_pitch_svg.py`

- [ ] **Step 1: Port `pitch_svg.py`**

Copy `poc/ankialign/pitch_svg.py` verbatim.

- [ ] **Step 2: Port the test**

Copy `poc/tests/test_pitch_svg.py` verbatim.

- [ ] **Step 3: Run**

Run: `uv run pytest tests/test_pitch_svg.py -v`
Expected: all pass.

- [ ] **Step 4: Commit**

```bash
git add ankialign/pitch_svg.py tests/test_pitch_svg.py
git commit -m "feat: port pure SVG pitch-graph generator"
```

---

## Phase 2 — Accent index, tokenizer, JRP engine

### Task 2.1: `jrp_fill/accent.py` + index smoke test

**Files:**
- Create: `ankialign/jrp_fill/accent.py`
- Test: `tests/test_jrp_accent.py`

- [ ] **Step 1: Port `accent.py`**

Copy `poc/ankialign/jrp_fill/accent.py`. Change `_DEFAULT` to resolve via paths:

```python
from ankialign.paths import ACCENT_INDEX as _DEFAULT
```

and delete the old `_DEFAULT = os.path.join(...)` line + now-unused `os` import if nothing else needs it (keep `gzip`, `marshal`).

- [ ] **Step 2: Port the test**

Copy `poc/tests/test_jrp_accent.py`; update the `IDX` path to `ankialign/data/accent_index.marshal.gz` (under the new tree) and keep the `skipif` guard.

- [ ] **Step 3: Run**

Run: `uv run pytest tests/test_jrp_accent.py -v`
Expected: pass (index is shipped, so the skip does not trigger).

- [ ] **Step 4: Commit**

```bash
git add ankialign/jrp_fill/accent.py tests/test_jrp_accent.py
git commit -m "feat: port accent index loader"
```

### Task 2.2: `jrp_fill/tokenize.py` (Sudachi)

**Files:**
- Create: `ankialign/jrp_fill/tokenize.py`
- Test: `tests/test_jrp_tokenize.py`

- [ ] **Step 1: Port `tokenize.py`**

Copy `poc/ankialign/jrp_fill/tokenize.py`, then change `from ._utils import hira, KANJI_RE` → `from .kana import hira, KANJI_RE`. Port the stoplists and merge logic intact (domain tuning). Apply the comment-discipline rule: keep the load-bearing rationale (why a lemma is stoplisted, why a merge fires) but trim decorative asides like "(user call)" to a plain statement.

- [ ] **Step 2: Port the test**

Copy `poc/tests/test_jrp_tokenize.py` verbatim.

- [ ] **Step 3: Run**

Run: `uv run pytest tests/test_jrp_tokenize.py -v`
Expected: all pass (downloads/uses `sudachidict-full`; first run may be slow).

- [ ] **Step 4: Commit**

```bash
git add ankialign/jrp_fill/tokenize.py tests/test_jrp_tokenize.py
git commit -m "feat: port Sudachi tokenizer -> Unit pipeline"
```

### Task 2.3: `jrp_fill/splice.py`, `engine.py`, `__init__.make_generator`

**Files:**
- Create: `ankialign/jrp_fill/splice.py`, `ankialign/jrp_fill/engine.py`
- Modify: `ankialign/jrp_fill/__init__.py`
- Test: `tests/test_jrp_splice.py`, `tests/test_jrp_engine.py`

- [ ] **Step 1: Port `splice.py`**

Copy `poc/ankialign/jrp_fill/splice.py`, then change `from ._utils import KANJI_RE` → `from .kana import KANJI_RE`. Keep the `from .tokenize import _NO_PITCH_LEMMAS, _NO_PITCH_FORMS` import.

- [ ] **Step 2: Port `engine.py`**

Copy `poc/ankialign/jrp_fill/engine.py`, then change `from ankialign import markup` (unchanged path — still valid). In the docstring of `build_plan`, replace "replacing previously-accepted gold" with "replacing previously-accepted markup". No logic change.

- [ ] **Step 3: Write `__init__.py`**

Replace the Task 1.2 stub with:

```python
"""Offline JRP sentence-markup generator (run headless).

Pure transforms: kana, unit, align, emit, splice (stdlib only).
Heavy deps:      accent (shipped index), tokenize (Sudachi).
Collection I/O:  engine (the `anki` library).
"""


def make_generator(conj_accent: bool = False):
    """Return generate(plain_text) -> JRP markup, the function engine.build_plan
    expects. Loads the accent index + Sudachi lazily (heavy) so importing this
    package stays cheap."""
    from .accent import AccentIndex
    from .tokenize import units
    from .emit import emit_sentence
    acc = AccentIndex.load()
    return lambda text: emit_sentence(units(text, acc, conj_accent=conj_accent))
```

- [ ] **Step 4: Port tests**

Copy `poc/tests/test_jrp_splice.py` and `poc/tests/test_jrp_engine.py` verbatim. If either uses "gold" in a name, rename to "reference".

- [ ] **Step 5: Run**

Run: `uv run pytest tests/test_jrp_splice.py tests/test_jrp_engine.py -v`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add ankialign/jrp_fill/splice.py ankialign/jrp_fill/engine.py ankialign/jrp_fill/__init__.py tests/test_jrp_splice.py tests/test_jrp_engine.py
git commit -m "feat: port JRP splice + plan/apply engine"
```

---

## Phase 3 — Pitch backfill

### Task 3.1: `pitch.py`

**Files:**
- Create: `ankialign/pitch.py`
- Test: `tests/test_pitch_engine.py`

- [ ] **Step 1: Port `pitch.py`**

Copy `poc/ankialign/pitch.py`, then:
- Replace the import block with:
  ```python
  from . import config, pitch_svg
  from .jrp_fill.accent import AccentIndex
  from .jrp_fill.kana import hira
  from .paths import ACCENT_INDEX as _INDEX_PATH
  ```
  and delete the old `_INDEX_PATH = os.path.join(...)` line and `import os`.
- Replace the literal `_FIELD = "Pitch Accent"` with `_FIELD = config.PITCH`.
- Replace `f'note:"{config.TARGET_NOTETYPE}"'` with `f'note:"{config.NOTETYPE}"'`.
- Replace `note["Term"]`, `note["Reading"]` accesses with `note[config.TERM]`, `note[config.READING]`.
- In the module docstring, remove the "gold -> NHK2016 -> ..." phrasing's use of "gold"; rewrite as "the shared dict-authoritative AccentIndex (NHK2016 + アクセント辞典v2)". Keep `SOURCES = ("NHK2016", "アクセント辞典v2")`.

- [ ] **Step 2: Port the test**

Copy `poc/tests/test_pitch_engine.py`; update any `TARGET_NOTETYPE`/field-name references to the new `config` names. If it builds an in-memory collection, ensure the notetype it creates is named via `config.NOTETYPE` and fields via the `config` constants.

- [ ] **Step 3: Run**

Run: `uv run pytest tests/test_pitch_engine.py -v`
Expected: all pass.

- [ ] **Step 4: Commit**

```bash
git add ankialign/pitch.py tests/test_pitch_engine.py
git commit -m "feat: port pitch-graph backfill"
```

---

## Phase 4 — Cloze

### Task 4.1: `cloze.py`

**Files:**
- Create: `ankialign/cloze.py`
- Test: `tests/test_cloze.py`

- [ ] **Step 1: Port `cloze.py`**

Copy `poc/ankialign/cloze.py`, then:
- Change imports: `from .jrp_fill.accent import AccentIndex`, `from .jrp_fill.kana import hira`, `from .jrp_fill.tokenize import units` (was `._utils`).
- Replace `config.TARGET_NOTETYPE` → `config.NOTETYPE`.
- Replace the hardcoded field lookups `fld["Term"]`, `fld["Sentence"]`, `fld["Reading"]`, `fld["Cloze Before"]`, `fld["Cloze Inside"]`, `fld["Cloze After"]` with `fld[config.TERM]`, `fld[config.SENTENCE]`, `fld[config.READING]`, `fld[config.CLOZE_BEFORE]`, `fld[config.CLOZE_INSIDE]`, `fld[config.CLOZE_AFTER]`.

- [ ] **Step 2: Port the test**

Copy `poc/tests/test_cloze.py`; route notetype/field names through `config`.

- [ ] **Step 3: Run**

Run: `uv run pytest tests/test_cloze.py -v`
Expected: all pass.

- [ ] **Step 4: Commit**

```bash
git add ankialign/cloze.py tests/test_cloze.py
git commit -m "feat: port cloze backfill"
```

---

## Phase 5 — Term audio

### Task 5.1: `audio.py`

**Files:**
- Create: `ankialign/audio.py`
- Test: `tests/test_audio.py`

- [ ] **Step 1: Port `audio.py`**

Copy `poc/ankialign/audio.py`, then:
- Replace `config.TARGET_NOTETYPE` → `config.NOTETYPE`.
- Replace the inline exclusion-join `" ".join(f"-deck:*{sub}*" ...)` in `_search` and `select_repitch_targets` with `config.deck_exclusion_search()`.
- Replace `note["Term"]`, `note["Reading"]`, `note["Term Audio"]` with `note[config.TERM]`, `note[config.READING]`, `note[config.TERM_AUDIO]`.
- Replace the `"Term Audio:"` empty-field search literals with f-strings using `config.TERM_AUDIO` (e.g. `f'"{config.TERM_AUDIO}:"'` and `f'-"{config.TERM_AUDIO}:"'`).

- [ ] **Step 2: Port the test**

Copy `poc/tests/test_audio.py`; route notetype/field names through `config`. Keep the in-memory `entries.db` fixtures.

- [ ] **Step 3: Run**

Run: `uv run pytest tests/test_audio.py -v`
Expected: all pass.

- [ ] **Step 4: Commit**

```bash
git add ankialign/audio.py tests/test_audio.py
git commit -m "feat: port term-audio fill + re-pitch"
```

---

## Phase 6 — Definitions

### Task 6.1: `defs/render.py`

**Files:**
- Create: `ankialign/defs/__init__.py` (empty), `ankialign/defs/render.py`
- Test: `tests/test_render_defs.py`

- [ ] **Step 1: Create the subpackage init**

Create `ankialign/defs/__init__.py` (empty).

- [ ] **Step 2: Port `render.py`**

Copy `poc/ankialign/render_defs.py` to `ankialign/defs/render.py` verbatim (pure; no import changes).

- [ ] **Step 3: Port the test**

Copy `poc/tests/test_render_defs.py` to `tests/test_render_defs.py`; change imports `from ankialign import render_defs` → `from ankialign.defs import render`.

- [ ] **Step 4: Run**

Run: `uv run pytest tests/test_render_defs.py -v`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add ankialign/defs/__init__.py ankialign/defs/render.py tests/test_render_defs.py
git commit -m "feat: port Yomitan structured-content renderer + def assembly"
```

### Task 6.2: `defs/build.py` (def index builder)

**Files:**
- Create: `ankialign/defs/build.py`
- Test: `tests/test_build_def_index.py`

- [ ] **Step 1: Port the builder**

Copy `poc/tools/build_def_index.py` to `ankialign/defs/build.py`, then:
- Remove the `sys.path.insert(...)` hack and `from ankialign import render_defs`; replace with `from ankialign.defs import render`.
- Replace the `render_defs.render_sc` call in `_index_jj` with `render.render_sc`.
- Keep `JJ_DICTS`/`JE_DICT` but import the canonical lists from config instead:
  ```python
  from ankialign import config
  JJ_DICTS = config.JJ_CHAIN
  JE_DICT = config.JE_DICT
  ```
- Update `build()` so its default paths come from `ankialign.paths` (`DICTS_DIR`, `DEFS_DB`, `DICT_STYLES`) and it calls `paths.ensure_var()` before writing.
- Add a module-level `def ensure(dicts_dir=None, db_path=None, styles_path=None) -> str:` that returns `"present"` if `db_path` exists, `"built"` after building when the dicts dir is populated, or `"no-source"` when the dicts dir is missing/empty. (This replaces the poc's `pipeline.ensure_def_index`.)

```python
def ensure(dicts_dir=None, db_path=None, styles_path=None) -> str:
    from ankialign import paths
    dicts_dir = dicts_dir or paths.DICTS_DIR
    db_path = db_path or paths.DEFS_DB
    styles_path = styles_path or paths.DICT_STYLES
    if os.path.exists(db_path):
        return "present"
    if not os.path.isdir(dicts_dir) or not os.listdir(dicts_dir):
        return "no-source"
    build(dicts_dir, db_path, styles_path)
    return "built"
```

- [ ] **Step 2: Port the test**

Copy `poc/tests/test_build_def_index.py`; change imports to `from ankialign.defs import build` and update any `build_def_index.X` references to `build.X`.

- [ ] **Step 3: Run**

Run: `uv run pytest tests/test_build_def_index.py -v`
Expected: all pass.

- [ ] **Step 4: Build the real index from the staged dicts (one-time, local)**

Run:
```bash
uv run python -c "from ankialign.defs import build; print(build.ensure())"
```
Expected: prints `built`; `var/defs.sqlite` (~1.4 GB) and `var/dict_styles.css` now exist.
Run: `ls -la var/defs.sqlite var/dict_styles.css`

- [ ] **Step 5: Commit** (code only; `var/` is gitignored)

```bash
git add ankialign/defs/build.py tests/test_build_def_index.py
git commit -m "feat: port def-index builder with ensure()"
```

### Task 6.3: `defs/index.py` + `defs/enrich.py`

**Files:**
- Create: `ankialign/defs/index.py`, `ankialign/defs/enrich.py`
- Test: `tests/test_enrich.py`

- [ ] **Step 1: Write `defs/index.py`** (the lookup half of the poc's `enrich.py`)

```python
"""Open + query the definition index (defs.sqlite)."""
import sqlite3

from ankialign import paths

_COLS = ("term", "reading", "dict_id", "kind", "sense_ord", "html")


def open_index(path=None):
    conn = sqlite3.connect(path or paths.DEFS_DB)
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

- [ ] **Step 2: Port `enrich.py`** (the note-rewriting half)

Copy `poc/ankialign/enrich.py` to `ankialign/defs/enrich.py`, then:
- Replace imports with:
  ```python
  from ankialign import config, template
  from ankialign.defs import index, render
  from ankialign.collection import in_scope_search
  ```
  (drop `convert`; `open_index`/`lookup` now come from `index`.)
- Delete the local `open_index`/`lookup`/`_COLS` (now in `index.py`); update `_compute` to call `index.lookup(conn, term)` and `render.choose_jj`/`render.numbered_glosses`/`render.assemble_meaning`/`render.source_label`.
- Replace `config.TARGET_NOTETYPE` → `config.NOTETYPE`; `config.ORIGINAL_MEANING_FIELD` → `config.ORIGINAL_MEANING`; `config.DEFINITION_SOURCE_FIELD` → `config.DEFINITION_SOURCE`; `config.DEF_INDEX_PATH` reference removed (use `index.open_index`).
- Replace `note["Meaning"]` with `note[config.DEFINITION]`, `note["Term"]`→`note[config.TERM]`, `note["Reading"]`→`note[config.READING]`.
- Replace `_in_scope_nids` body `convert._search(config.TARGET_NOTETYPE)` with `in_scope_search()` (added in Task 9.1; for now reference it — Task 9.1 lands before pipeline wiring, and this module is only imported by the pipeline).
- `apply_dict_styles`: read `paths.DICT_STYLES` by default; keep marker-guarded append. Keep calling `template.apply(col)` then `apply_dict_styles(col)` in `enrich_notes`.

> Dependency note: `defs/enrich.py` imports `ankialign.collection` (Task 9.1) and `ankialign.template` (Task 8.1). Those tasks have no reverse dependency on `defs`, so land 8.1 and 9.1 before running this task's tests; the plan orders them earlier where possible, but if executing strictly in number order, run Task 6.3 Step 4 after Tasks 8.1 and 9.1.

- [ ] **Step 3: Port the test**

Copy `poc/tests/test_enrich.py`; change imports to `from ankialign.defs import enrich, index`, route notetype/fields through `config`, and point `open_index` at a small in-memory/fixture sqlite as the poc test does.

- [ ] **Step 4: Run**

Run: `uv run pytest tests/test_enrich.py -v`
Expected: all pass (after Tasks 8.1 + 9.1 are present).

- [ ] **Step 5: Commit**

```bash
git add ankialign/defs/index.py ankialign/defs/enrich.py tests/test_enrich.py
git commit -m "feat: port definition lookup + note enrichment"
```

---

## Phase 7 — Dedupe

### Task 7.1: `dedupe.py`

**Files:**
- Create: `ankialign/dedupe.py`
- Test: `tests/test_dedupe.py`

- [ ] **Step 1: Port `dedupe.py`**

Copy `poc/ankialign/dedupe.py`, then:
- Replace `config.TARGET_NOTETYPE` → `config.NOTETYPE`.
- The field index uses `ix["Term"]` / `ix.get("Reading", ti)`; replace with `ix[config.TERM]` / `ix.get(config.READING, ti)`.
- In the module docstring, replace the "do a sync and, if Anki flags a conflict, choose Upload to AnkiWeb" note with: "the tool re-uploads via `sync.push` after dedupe." (No logic change.)

- [ ] **Step 2: Port the test**

Copy `poc/tests/test_dedupe.py`; route notetype/fields through `config`.

- [ ] **Step 3: Run**

Run: `uv run pytest tests/test_dedupe.py -v`
Expected: all pass.

- [ ] **Step 4: Commit**

```bash
git add ankialign/dedupe.py tests/test_dedupe.py
git commit -m "feat: port duplicate merge"
```

---

## Phase 8 — Template (first-class card design)

### Task 8.1: `template.py`

**Files:**
- Create: `ankialign/template.py`
- Test: `tests/test_template.py`

This **replaces** the poc's incremental self-healing `template.py`. The canonical design lives in `card/kinomoto/`; apply writes it wholesale, then appends generated dict styles.

- [ ] **Step 1: Write `template.py`**

```python
"""Apply the repo-owned Kinomoto card design to the note type.

The canonical front/back/style live in card/kinomoto/ (first-class repo files).
apply() writes them onto the note type verbatim, then appends the generated
scoped dictionary styles (var/dict_styles.css) after a marker so a def-index
rebuild can refresh them without disturbing the authored design."""
import os

from . import config, paths

_STYLE_MARKER = "/* ---- AnkiAlign dict styles (generated) ---- */"


def _read(name: str) -> str:
    with open(os.path.join(paths.CARD_DIR, name), encoding="utf-8") as fh:
        return fh.read()


def apply(col) -> None:
    m = col.models.by_name(config.NOTETYPE)
    if m is None:
        raise RuntimeError("target notetype missing: " + config.NOTETYPE)
    base_css = _read("style.css").rstrip()
    dict_css = ""
    if os.path.exists(paths.DICT_STYLES):
        with open(paths.DICT_STYLES, encoding="utf-8") as fh:
            dict_css = "\n\n" + _STYLE_MARKER + "\n" + fh.read()
    m["css"] = base_css + dict_css
    tmpl = m["tmpls"][0]
    tmpl["qfmt"] = _read("front.html")
    tmpl["afmt"] = _read("back.html")
    col.models.update_dict(m)
```

- [ ] **Step 2: Write `tests/test_template.py`**

```python
import os
import tempfile

import pytest
from anki.collection import Collection

from ankialign import config, paths, template


def _col(tmp):
    col = Collection(os.path.join(tmp, "c.anki2"))
    m = col.models.new(config.NOTETYPE)
    for fname in (config.TERM, config.SENTENCE, config.CLOZE_INSIDE):
        col.models.add_field(m, col.models.new_field(fname))
    t = col.models.new_template("jidoujisho Kinomoto")
    t["qfmt"] = "OLD FRONT"
    t["afmt"] = "OLD BACK"
    col.models.add_template(m, t)
    m["css"] = "OLD CSS"
    col.models.add(m)
    return col


def test_apply_writes_repo_design(tmp_path, monkeypatch):
    col = _col(str(tmp_path))
    try:
        template.apply(col)
        m = col.models.by_name(config.NOTETYPE)
        assert m["tmpls"][0]["qfmt"] == open(
            os.path.join(paths.CARD_DIR, "front.html"), encoding="utf-8").read()
        assert m["tmpls"][0]["afmt"] == open(
            os.path.join(paths.CARD_DIR, "back.html"), encoding="utf-8").read()
        assert m["css"].startswith(open(
            os.path.join(paths.CARD_DIR, "style.css"), encoding="utf-8").read().rstrip())
        assert "OLD CSS" not in m["css"]
    finally:
        col.close()


def test_apply_appends_dict_styles_when_present(tmp_path, monkeypatch):
    styles = tmp_path / "dict_styles.css"
    styles.write_text(".aa-jj{color:red}", encoding="utf-8")
    monkeypatch.setattr(paths, "DICT_STYLES", str(styles))
    col = _col(str(tmp_path))
    try:
        template.apply(col)
        m = col.models.by_name(config.NOTETYPE)
        assert template._STYLE_MARKER in m["css"]
        assert ".aa-jj{color:red}" in m["css"]
    finally:
        col.close()


def test_apply_idempotent(tmp_path):
    col = _col(str(tmp_path))
    try:
        template.apply(col)
        css1 = col.models.by_name(config.NOTETYPE)["css"]
        template.apply(col)
        css2 = col.models.by_name(config.NOTETYPE)["css"]
        assert css1 == css2
    finally:
        col.close()
```

- [ ] **Step 3: Run**

Run: `uv run pytest tests/test_template.py -v`
Expected: 3 passed.

- [ ] **Step 4: Commit**

```bash
git add ankialign/template.py tests/test_template.py
git commit -m "feat: apply repo-owned card design wholesale + generated dict styles"
```

---

## Phase 9 — Collection helpers

### Task 9.1: `collection.py`

**Files:**
- Create: `ankialign/collection.py`
- Test: `tests/test_collection.py`

- [ ] **Step 1: Write `collection.py`**

```python
"""Open the working-copy collection, in-scope note search, and verified backup.

The tool operates on its OWN collection file (paths.WORKING_COLLECTION), never
the Anki desktop profile."""
import os
import time

from anki.collection import Collection

from . import config, paths


def open_working() -> Collection:
    """Open (creating if absent) the tool's working-copy collection."""
    paths.ensure_var()
    return Collection(paths.WORKING_COLLECTION)


def in_scope_search() -> str:
    """Anki search for in-scope notes of the target note type."""
    excl = config.deck_exclusion_search()
    return f'note:"{config.NOTETYPE}" {excl}'.strip()


def backup(col, backup_dir: str | None = None) -> str:
    """Force a full backup and verify a FRESH .colpkg landed. Returns the dir.

    create_backup(force=True) writes nothing when there are no pending changes
    and the dir may hold old backups, so require a file newer than this call."""
    backup_dir = backup_dir or os.path.join(paths.VAR_DIR, "backups")
    os.makedirs(backup_dir, exist_ok=True)
    before = time.time()
    col.create_backup(backup_folder=backup_dir, force=True, wait_for_completion=True)
    fresh = [
        f for f in os.listdir(backup_dir)
        if f.endswith(".colpkg")
        and os.path.getmtime(os.path.join(backup_dir, f)) >= before - 1
    ]
    if not fresh:
        raise RuntimeError(
            f"no fresh backup created in {backup_dir}; aborting before mutation")
    return backup_dir
```

- [ ] **Step 2: Write `tests/test_collection.py`**

```python
import os

from anki.collection import Collection

from ankialign import collection, config


def test_in_scope_search_shape():
    s = collection.in_scope_search()
    assert s.startswith(f'note:"{config.NOTETYPE}"')
    assert "-deck:*Core2.3k*" in s


def test_backup_creates_fresh_colpkg(tmp_path):
    col = Collection(os.path.join(tmp_path, "c.anki2"))
    try:
        bdir = collection.backup(col, str(tmp_path / "backups"))
        pkgs = [f for f in os.listdir(bdir) if f.endswith(".colpkg")]
        assert pkgs, "expected a .colpkg backup"
    finally:
        col.close()
```

- [ ] **Step 3: Run**

Run: `uv run pytest tests/test_collection.py -v`
Expected: 2 passed.

- [ ] **Step 4: Commit**

```bash
git add ankialign/collection.py tests/test_collection.py
git commit -m "feat: working-copy open, in-scope search, verified backup"
```

---

## Phase 10 — Headless AnkiWeb sync

### Task 10.1: `sync.py`

**Files:**
- Create: `ankialign/sync.py`
- Test: `tests/test_sync.py`

The anki API: `col.sync_login(user, pw, endpoint) -> SyncAuth`; `col.sync_collection(auth, sync_media) -> SyncOutput` whose `.required` is a `SyncOutput.ChangesRequired` enum (`NO_CHANGES`, `NORMAL_SYNC`, `FULL_SYNC`, `FULL_DOWNLOAD`, `FULL_UPLOAD`); `col.full_upload_or_download(auth=, server_usn=, upload=)` for full transfers; `col.sync_media(auth)` + `col.media_sync_status()`. The branch decision is factored into a pure helper so it is unit-testable without a server.

- [ ] **Step 1: Write the failing test**

```python
from ankialign import sync


def test_resolve_pull_actions():
    R = sync.Required
    assert sync.resolve_pull(R.NO_CHANGES) == "none"
    assert sync.resolve_pull(R.NORMAL_SYNC) == "normal"
    assert sync.resolve_pull(R.FULL_DOWNLOAD) == "download"
    # On pull, a server that wants a full upload means our local copy is ahead;
    # for a fresh working copy we still treat FULL_SYNC as "download".
    assert sync.resolve_pull(R.FULL_SYNC) == "download"


def test_resolve_push_actions():
    R = sync.Required
    assert sync.resolve_push(R.NO_CHANGES) == "none"
    assert sync.resolve_push(R.NORMAL_SYNC) == "normal"
    # Schema change from enrichment -> server requires a full upload.
    assert sync.resolve_push(R.FULL_SYNC) == "upload"
    assert sync.resolve_push(R.FULL_UPLOAD) == "upload"
```

- [ ] **Step 2: Run to verify it fails**

Run: `uv run pytest tests/test_sync.py -v`
Expected: FAIL (module `sync` not found).

- [ ] **Step 3: Write `sync.py`**

```python
"""Headless AnkiWeb sync for the working-copy collection.

pull() brings the latest down (used at the start of every run, dry-run included).
push() uploads after --apply; enrichment adds fields, so a full upload is the
common outcome. Credentials come from the environment (config)."""
from anki.collection import Collection
from anki.sync import SyncOutput

from . import config

Required = SyncOutput.ChangesRequired


def login(col: Collection):
    user, pw = config.ankiweb_credentials()
    return col.sync_login(user, pw, None)


def resolve_pull(required) -> str:
    if required == Required.NO_CHANGES:
        return "none"
    if required == Required.NORMAL_SYNC:
        return "normal"
    # Any full requirement on pull -> download the server copy (our working copy
    # is disposable; the server is the source of truth at the start of a run).
    return "download"


def resolve_push(required) -> str:
    if required == Required.NO_CHANGES:
        return "none"
    if required == Required.NORMAL_SYNC:
        return "normal"
    # FULL_SYNC / FULL_UPLOAD -> upload our enriched copy.
    return "upload"


def pull(col: Collection, auth) -> str:
    out = col.sync_collection(auth, sync_media=True)
    action = resolve_pull(out.required)
    if action == "download":
        col.full_upload_or_download(auth=auth, server_usn=out.server_usn, upload=False)
    col.sync_media(auth)
    return action


def push(col: Collection, auth) -> str:
    out = col.sync_collection(auth, sync_media=True)
    action = resolve_push(out.required)
    if action == "upload":
        col.full_upload_or_download(auth=auth, server_usn=out.server_usn, upload=True)
    col.sync_media(auth)
    return action
```

- [ ] **Step 4: Run to verify it passes**

Run: `uv run pytest tests/test_sync.py -v`
Expected: 2 passed.

- [ ] **Step 5: Manual end-to-end check (requires real AnkiWeb creds; not part of CI)**

Run (with `ANKIWEB_USERNAME`/`ANKIWEB_PASSWORD` exported):
```bash
uv run python - <<'PY'
from ankialign import collection, sync
col = collection.open_working()
try:
    auth = sync.login(col)
    print("pull:", sync.pull(col, auth))
finally:
    col.close()
PY
```
Expected: prints `pull: download` (first run) or `pull: normal`/`none`; `var/collection.anki2` populated with your real notes afterward.

- [ ] **Step 6: Commit**

```bash
git add ankialign/sync.py tests/test_sync.py
git commit -m "feat: headless AnkiWeb pull/push"
```

---

## Phase 11 — Pipeline & CLI

### Task 11.1: `pipeline.py`

**Files:**
- Create: `ankialign/pipeline.py`
- Test: `tests/test_pipeline.py`

- [ ] **Step 1: Write `pipeline.py`**

```python
"""Headless enrichment suite. Order: audio -> pitch -> sentences -> definitions
-> cloze. Dedupe is destructive and lives in its own command, not here.

Dry-run unless apply=True; a verified backup is taken before any write."""
import os

from . import audio, cloze, config, collection, pitch
from .defs import build as def_build, enrich


def _step_audio(col, apply):
    from . import paths
    addons = os.environ.get("ANKI_ADDONS_DIR", os.path.expanduser(
        "~/.local/share/Anki2/addons21"))
    located = audio.locate(addons)
    if located is None:
        return {"skipped": "local-audio-yomichan entries.db not found"}
    db_path, ufd = located
    import sqlite3
    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        if apply:
            res = audio.run_backfill(col, conn, ufd)
            return {"filled": dict(res["filled"]), "errors": len(res.get("errors", []))}
        plan = audio.plan_backfill(col, conn, ufd)
        return {"will_fill": plan["filled"], "by_source": dict(plan["by_source"]),
                "none": plan["nothing"], "missing_file": plan["missing_file"]}
    finally:
        conn.close()


def _step_sentences(col, apply, overwrite):
    from .jrp_fill import engine, make_generator
    gen = make_generator()
    if apply:
        n = engine.apply_to_fixpoint(col, config.NOTETYPE, config.SENTENCE, gen,
                                     regenerate=overwrite)
        return {"filled": n}
    plan = engine.build_plan(col, config.NOTETYPE, config.SENTENCE, gen,
                             regenerate=overwrite)
    return {"filled": len(plan)}


def _step_enrich(col, apply, def_index, overwrite):
    if def_index == "no-source":
        return {"skipped": "defs.sqlite missing and no dict source to build it"}
    from .defs import index
    conn = index.open_index()
    try:
        if apply:
            return enrich.enrich_notes(col, conn, overwrite=overwrite)
        plan = enrich.build_plan(col, conn, overwrite=overwrite)
        return {"to_write": plan["to_write"], **plan["counts"]}
    finally:
        conn.close()


def run(col, *, apply=False, overwrite_pitch=False, overwrite_cloze=False,
        overwrite_sentences=False, overwrite_defs=False) -> dict:
    def_index = def_build.ensure()
    scanned = len(col.find_notes(f'note:"{config.NOTETYPE}"'))
    out = {"def_index": def_index, "scanned": scanned, "backup": None, "steps": {}}

    if apply:
        out["backup"] = collection.backup(col)

    out["steps"]["audio"] = _step_audio(col, apply)
    out["steps"]["pitch"] = (pitch.backfill_pitch(col, overwrite=overwrite_pitch) if apply
                             else pitch.build_pitch_plan(col, overwrite=overwrite_pitch)["counts"])
    out["steps"]["sentences"] = _step_sentences(col, apply, overwrite_sentences)
    out["steps"]["enrich"] = _step_enrich(col, apply, def_index, overwrite_defs)
    out["steps"]["cloze"] = cloze.run(col, apply=apply, overwrite=overwrite_cloze)
    return out
```

- [ ] **Step 2: Write `tests/test_pipeline.py`** (dry-run wiring on an in-memory collection)

```python
import os

from anki.collection import Collection

from ankialign import config, pipeline


def _kino_col(tmp):
    col = Collection(os.path.join(tmp, "c.anki2"))
    m = col.models.new(config.NOTETYPE)
    for fname in (config.TERM, config.READING, config.SENTENCE, config.PITCH,
                  config.CLOZE_BEFORE, config.CLOZE_INSIDE, config.CLOZE_AFTER,
                  config.TERM_AUDIO, config.DEFINITION):
        col.models.add_field(m, col.models.new_field(fname))
    t = col.models.new_template("jidoujisho Kinomoto")
    t["qfmt"], t["afmt"] = "{{Term}}", "{{Reading}}"
    col.models.add_template(m, t)
    col.models.add(m)
    return col


def test_dry_run_reports_steps(tmp_path, monkeypatch):
    # Force audio + defs to the skip path so the test needs no external assets.
    monkeypatch.setenv("ANKI_ADDONS_DIR", str(tmp_path / "nope"))
    from ankialign.defs import build
    monkeypatch.setattr(build, "ensure", lambda: "no-source")
    col = _kino_col(str(tmp_path))
    try:
        res = pipeline.run(col, apply=False)
        assert set(res["steps"]) == {"audio", "pitch", "sentences", "enrich", "cloze"}
        assert "skipped" in res["steps"]["audio"]
        assert "skipped" in res["steps"]["enrich"]
        assert res["backup"] is None
    finally:
        col.close()
```

- [ ] **Step 3: Run**

Run: `uv run pytest tests/test_pipeline.py -v`
Expected: passed.

- [ ] **Step 4: Commit**

```bash
git add ankialign/pipeline.py tests/test_pipeline.py
git commit -m "feat: enrichment pipeline orchestration"
```

### Task 11.2: `cli.py`

**Files:**
- Create: `ankialign/cli.py`
- Test: `tests/test_cli.py`

- [ ] **Step 1: Write `cli.py`**

```python
"""Console entry point: `anki-align`.

  uv run anki-align            # pull latest, dry-run preview of the suite
  uv run anki-align --apply    # pull, backup, write, push to AnkiWeb
  uv run anki-align --apply --overwrite   # re-assert pitch/sentences/defs/cloze
  uv run anki-align dedupe --apply         # destructive duplicate merge (separate)
"""
import argparse
import sys

from . import collection, dedupe as _dedupe, pipeline, sync


def _render(res: dict, apply: bool) -> None:
    s = res["steps"]
    print(f"\n  scanned   {res['scanned']} cards")
    if res["backup"]:
        print(f"  backup    {res['backup']}")
    print()
    a = s["audio"]
    if "skipped" in a:
        print(f"  audio     — skipped: {a['skipped']}")
    elif apply:
        print(f"  audio     {sum(a['filled'].values())} filled")
    else:
        print(f"  audio     {a['will_fill']} to fill · {a['none']} no match")
    p = s["pitch"]
    print(f"  pitch     {p['filled']} · {p['not_found']} not in dict · {p['skipped']} no reading")
    print(f"  sentence  {s['sentences']['filled']} JRP markup")
    e = s["enrich"]
    if "skipped" in e:
        print(f"  defs      — skipped: {e['skipped']}")
    elif apply:
        print(f"  defs      {e['written']} written · {e['dropped']} dropped")
    else:
        print(f"  defs      {e['to_write']} to write · {e['nohit']} no-hit")
    print(f"  cloze     {s['cloze']['filled']} · {s['cloze']['skipped']} unlocatable")
    print()
    print("  ✓ applied + pushed." if apply else "  dry run — pass --apply to write.")
    print()


def _render_dedupe(res: dict, apply: bool) -> None:
    print(f"\n  scanned    {res['scanned']} cards")
    print(f"  dup groups {res['groups']}")
    print(f"  removable  {res['removed']}")
    print("\n  ✓ merged + pushed." if apply else "\n  dry run — pass --apply to write.")
    print()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="anki-align",
                                 description="Post-sync Anki enrichment.")
    ap.add_argument("command", nargs="?", default="rerun", choices=["rerun", "dedupe"])
    ap.add_argument("--apply", action="store_true", help="write + push (else dry run)")
    ap.add_argument("--overwrite", action="store_true",
                    help="re-assert pitch, sentences, defs AND cloze on every card")
    ap.add_argument("--overwrite-pitch", action="store_true")
    ap.add_argument("--overwrite-sentences", action="store_true")
    ap.add_argument("--overwrite-defs", action="store_true")
    ap.add_argument("--overwrite-cloze", action="store_true")
    args = ap.parse_args(argv)

    col = collection.open_working()
    try:
        auth = sync.login(col)
        print(f"  sync      pull: {sync.pull(col, auth)}")
        if args.command == "dedupe":
            if args.apply:
                collection.backup(col)
            res = _dedupe.run(col, apply=args.apply)
            if args.apply:
                print(f"  sync      push: {sync.push(col, auth)}")
            _render_dedupe(res, args.apply)
        else:
            res = pipeline.run(
                col, apply=args.apply,
                overwrite_pitch=args.overwrite_pitch or args.overwrite,
                overwrite_sentences=args.overwrite_sentences or args.overwrite,
                overwrite_defs=args.overwrite_defs or args.overwrite,
                overwrite_cloze=args.overwrite_cloze or args.overwrite)
            if args.apply:
                print(f"  sync      push: {sync.push(col, auth)}")
            _render(res, args.apply)
    finally:
        col.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 2: Write `tests/test_cli.py`** (argument parsing only; no network)

```python
from ankialign import cli


def test_overwrite_fans_out(monkeypatch):
    captured = {}

    def fake_run(col, **kw):
        captured.update(kw)
        return {"scanned": 0, "backup": None, "steps": {
            "audio": {"skipped": "x"}, "pitch": {"filled": 0, "not_found": 0, "skipped": 0},
            "sentences": {"filled": 0}, "enrich": {"skipped": "x"},
            "cloze": {"filled": 0, "skipped": 0}}}

    class FakeCol:
        def close(self): pass

    monkeypatch.setattr(cli.collection, "open_working", lambda: FakeCol())
    monkeypatch.setattr(cli.sync, "login", lambda col: None)
    monkeypatch.setattr(cli.sync, "pull", lambda col, auth: "none")
    monkeypatch.setattr(cli.pipeline, "run", fake_run)
    cli.main(["--overwrite"])
    assert captured["overwrite_pitch"] and captured["overwrite_cloze"]
    assert captured["overwrite_sentences"] and captured["overwrite_defs"]
```

- [ ] **Step 3: Run**

Run: `uv run pytest tests/test_cli.py -v`
Expected: passed.

- [ ] **Step 4: Commit**

```bash
git add ankialign/cli.py tests/test_cli.py
git commit -m "feat: CLI entry point with pull/dry-run/apply/push"
```

---

## Phase 12 — Groundtruth audits & final wiring

### Task 12.1: Port the groundtruth (collection-audit) suites

**Files:**
- Create: `tests/test_markup_groundtruth.py`, `tests/test_pitch_groundtruth.py`, `tests/test_jrp_groundtruth.py`
- Optionally: a `var/collection.anki2` symlink for local runs

These audit the engine against the accents/markup already in the real collection. They `skipif` the collection/index is absent, so CI without the collection is green; locally they run against the synced working copy.

- [ ] **Step 1: Port the three suites**

Copy each from `poc/tests/`, then:
- Repoint `DB` from `"../collection.anki2"` to `ankialign.paths.WORKING_COLLECTION`.
- Repoint `IDX` to `ankialign.paths.ACCENT_INDEX`.
- Update imports: `from ankialign.jrp_fill.accent import AccentIndex`, `from ankialign.pitch import primary_accent`, etc.
- Rename every `gold` identifier/printout to `observed` (the value read back from the existing SVG/markup).
- Route field names (`Term`, `Reading`, `Pitch Accent`, `Sentence`) through `config`.

- [ ] **Step 2: Run (expect skips without a synced collection)**

Run: `uv run pytest tests/test_markup_groundtruth.py tests/test_pitch_groundtruth.py tests/test_jrp_groundtruth.py -v`
Expected: all SKIPPED if `var/collection.anki2` absent; otherwise pass the coverage/agreement thresholds.

- [ ] **Step 3: Commit**

```bash
git add tests/test_markup_groundtruth.py tests/test_pitch_groundtruth.py tests/test_jrp_groundtruth.py
git commit -m "test: port collection-audit groundtruth suites"
```

### Task 12.2: Full suite green + author CLAUDE.md run notes

**Files:**
- Modify: `AGENTS.md` (symlinked as `CLAUDE.md`)

- [ ] **Step 1: Run the whole suite**

Run: `uv run pytest -q`
Expected: all pass (collection-audit suites skipped unless a working copy is present). Investigate and fix any failure before proceeding.

- [ ] **Step 2: Add a short "Running" section to `AGENTS.md`**

Append (keep terse, stack already documented):

```markdown
## Running

- `ANKIWEB_USERNAME`/`ANKIWEB_PASSWORD` must be set; the tool keeps its own
  collection at `var/collection.anki2` (never the desktop profile).
- `uv run anki-align` — pull latest, dry-run preview. `--apply` writes + pushes.
- `uv run anki-align dedupe --apply` — destructive duplicate merge (separate).
- First run builds `var/defs.sqlite` from `dicts/` (the J-J chain + JMdict);
  the accent index ships in `ankialign/data/`.
```

- [ ] **Step 3: Commit**

```bash
git add AGENTS.md
git commit -m "docs: CLAUDE.md run notes"
```

### Task 12.3: Manual end-to-end dry run

- [ ] **Step 1: Real dry run against AnkiWeb**

Run (creds exported): `uv run anki-align`
Expected: prints `sync pull: …`, then per-step dry-run counts, ends with "dry run — pass --apply to write." `var/collection.anki2` now mirrors AnkiWeb; no upload happened.

- [ ] **Step 2: Spot-check, then a real apply when satisfied**

Run: `uv run anki-align --apply`
Expected: backup path printed, steps show written counts, `sync push: upload` (schema changed by enrich), no error. Verify on AnkiWeb / a device that audio, pitch graphs, markup, definitions, and cloze populated on recently-added cards.

### Task 12.4: Remove throwaway scaffolding

Only after Task 12.3 confirms a clean end-to-end run.

- [ ] **Step 1: Delete the plan and the PRD sketch**

```bash
git rm docs/superpowers/plans/2026-06-08-anki-align-rebuild.md prd-sketch.md
```

- [ ] **Step 2: Confirm durable docs remain**

Run: `ls PRD.md AGENTS.md CLAUDE.md`
Expected: all three present (`CLAUDE.md` is a symlink to `AGENTS.md`).

- [ ] **Step 3: Commit**

```bash
git commit -m "chore: remove build scaffolding (plan + prd sketch)"
```

---

## Self-Review Notes

- **Spec coverage:** audio (5.1), pitch (3.1), sentence JRP (Phases 1–2 + 11), definitions J-J-on-top + J-E-below (6.1–6.3, layout preserved by `render.assemble_meaning`), cloze (4.1), dedupe (7.1), repo-owned template (0.4 + 8.1), AnkiWeb sync own-working-copy + dry-run-pulls (10.1, 11.2), dry-run default / `--apply` writes + backup (11.1–11.2). All PRD items mapped.
- **Cross-task type consistency:** field access everywhere via `config.<ROLE>` constants; notetype via `config.NOTETYPE`; def lookup via `defs.index.open_index/lookup`; render via `defs.render`; sync branch via `sync.resolve_pull/resolve_push`.
- **Known ordering dependency:** `defs/enrich.py` (6.3) imports `template` (8.1) and `collection` (9.1); run 8.1 and 9.1 before 6.3's tests (noted in 6.3).
- **No "gold":** every ported file/test has the term renamed to "reference"/"observed".
