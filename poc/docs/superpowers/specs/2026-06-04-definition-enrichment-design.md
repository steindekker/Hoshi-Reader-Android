# Definition Enrichment — Design

**Date:** 2026-06-04
**Goal:** Replace each vocab card's cluttered, mixed-source `Meaning` with a clean
**Japanese-base definition** (monolingual J-J) plus short **numbered English glosses**
below — sourced by dictionary lookup, applied as an offline, reversible, opt-in pass
over the unified Kinomoto notes.

Builds on the dictionary research and data cleanup in `research/dict-compare/`
(`coverage.py`, `gap.py`, `check_others.py`) and `research/dictionaries-2026/REPORT.md`.
Coverage achieved on the cleaned collection: **J-J 99.0%, any-def 99.8%**.

---

## 1. Dictionaries

**J-J chain (first hit wins, in order):** 三省堂国語辞典 8 → 広辞苑 7 → 実用日本語表現辞典 → 大辞泉.
Only the **first** dictionary in the chain that has the word supplies the J-J block (not stacked).

**J-E:** JMdict — numbered senses, glosses only, **capped at 4** (`1. a; b; c` / `2. …`).

Source dictionaries are the user's local Yomitan zips, already unzipped under
`research/dict-compare/dicts/` (gitignored). Format 3 term banks:
`[expression, reading, defTags, rules, score, [definitions], sequence, termTags]`.
JMdict stores **one sense per entry**, with the sense number as the first token of `defTags`
and descending `score`.

---

## 2. Architecture — two phases

### Phase 1 — offline index build: `tools/build_def_index.py`
Standalone script (reuses the `research/dict-compare` parsing). Walks the 5 dictionaries
and writes a SQLite DB at **`ankialign/user_files/defs.sqlite`** (gitignored; rebuilt only
when dictionaries change). One row per usable entry:

```sql
CREATE TABLE defs (
  term       TEXT NOT NULL,
  reading    TEXT NOT NULL,   -- '' if the dict stored none
  dict_id    TEXT NOT NULL,   -- 'sankoku8' | 'koujien7' | 'jitsuyo' | 'daijisen' | 'jmdict'
  kind       TEXT NOT NULL,   -- 'jj' | 'je'
  sense_ord  INTEGER NOT NULL,-- JMdict sense order (0 for J-J)
  html       TEXT NOT NULL    -- J-J: rendered native Yomitan HTML; J-E: one sense's '; '-joined glosses
);
CREATE INDEX defs_key ON defs (term, reading);
```

- **J-J `html`** = native Yomitan structured content rendered to HTML (recursive renderer:
  preserves `tag`, inline `style`, `data-*` attributes so each dict's own look survives;
  drops `type:"image"`). This is the `render_sc` already prototyped in
  `research/dict-compare/mockup_enriched.py`.
- **J-E `html`** = per sense, the `data.content=="glossary"` `<li>` gloss texts joined by `"; "`,
  one row per sense with `sense_ord` from `defTags`.
- **Native styling is preserved.** `render_sc` keeps each node's inline `style` (font-size
  variation) and renders `ruby`/`rt` (furigana) and lists. Dicts that ship a `styles.css`
  (大辞泉 among the chain; 三省堂/広辞苑/実用 do not — they style via inline `style` + ruby)
  must have that CSS **extracted and emitted** by the build into
  `ankialign/user_files/dict_styles.css`, each rule scoped to `.aa-jj[data-dict="<id>"]`,
  for inclusion in the card CSS (§4). Verified in `mockup_enriched.py`: without this the
  definitions render flat; with it, furigana + size variation return.

### Phase 2 — in-Anki enrichment pass
Two new add-on modules, mirroring the existing `markup.py` (pure) / `convert.py` (anki-facing) split:

- **`ankialign/render_defs.py` — pure, no `anki`/`aqt` (headless-tested):**
  - `numbered_glosses(sense_rows) -> str` — order by `sense_ord`, cap 4, render `1. … / 2. …` joined by `<br>`.
  - `assemble_meaning(jj_html, je_html) -> str` — wrap into the card blocks (§4).
  - `choose_jj(rows, reading) -> (dict_id, html) | None` — chain order + reading disambiguation (§3).
- **`ankialign/enrich.py` — anki-facing:**
  - `open_index(path)` / lookup helpers over `defs.sqlite`.
  - `build_plan(col, db)` — read-only dry run: counts (enrich / english-only / no-hit) + samples + errors.
  - `enrich_notes(col, db)` — the mutation (§5).
  - Reuses `convert.backup_collection`.

### Wiring
New Tools-menu action **“AnkiAlign: Enrich definitions…”** → a `preview.py`-style dialog
(dry-run summary + Backup&&Enrich). The schema change (adding fields) runs on the **main
thread under `mw.progress`** with the `mod_schema(check=True)` acknowledgement, exactly like
the unify flow (a CollectionOp worker thread cannot run the schema hook).

---

## 3. Lookup & disambiguation

Key = note `(Term, Reading)`.

1. **J-J:** for each dict in chain order, query `defs` rows with `kind='jj'` for that `term`.
   Pick the entry whose `reading == Reading` (exact); else the entry with `reading == ''`;
   else skip to the next dict. First dict that yields an entry wins → `(dict_id, html)`.
2. **J-E:** all `kind='je'` rows for `(term, reading)` (reading-aligned the same way) →
   `numbered_glosses`.

**Katakana-homograph guard:** when `Term` is wholly kana/katakana and multiple J-J entries
share the reading (e.g. `ヤギ` → goat vs. typhoon name), prefer the entry that also has a
JMdict sense (i.e. the everyday word) before falling back to the first match. Recorded so
these can be spot-checked via the `Definition Source` field.

---

## 4. Assembled `Meaning`

```html
<div class="aa-jj" data-dict="sankoku8"> …native Yomitan J-J HTML… </div>
<details class="aa-je" open><summary>English</summary> 1. a; b; c<br>2. z; y; x </details>
```

- **English is a collapsible `<details>`** (native tap-to-toggle, works on desktop + AnkiDroid,
  no JS). The Japanese J-J block stays always-visible on top. The `open` attribute is baked in
  per `config.ENGLISH_COLLAPSED_DEFAULT` (default `False` → rendered `<details open>`,
  i.e. visible-but-collapsible; flip the flag + re-enrich to default-collapsed).
- J-J block on top, English below. The **card CSS** carries three layers, in order:
  1. Each native dict `styles.css`, scoped to `.aa-jj[data-dict="<id>"]` (from the build's
     `dict_styles.css`) — restores 大辞泉's look and any other dict that ships one.
  2. **Furigana / list polish** owned by the feature: `.aa-jj ruby rt { font-size:.55em }`,
     `ul/ol/li` spacing, subtle link styling, the `.aa-je` divider + muted English. (These are
     the rules `mockup_enriched.py`'s `AA_CSS` proved out.)
  3. **広辞苑-resembles-三省堂** overrides (INCLUDED): map 広辞苑's `data-name` spans onto
  三省堂's look (font-size, spacing, sense-marker style). Iterated visually against
  `research/dict-compare/`. Scoped to `.aa-jj[data-dict="koujien7"]`, so it only affects
  enriched 広辞苑 blocks — never a jidoujisho-created card (which has no `.aa-jj`).
  Colors/serif/dark-mode come from the existing template styling.
- **English-only** cards (no J-J hit): only the `aa-je` `<details>` block.
- **No on-card source label** — the source lives in the `Definition Source` field (§5).
- **jidoujisho-safe:** all enrichment CSS targets `.aa-jj`/`.aa-je` wrappers that exist only on
  enriched cards; a card freshly created by jidoujisho (plain `Meaning`, no wrappers) renders
  unchanged. Added fields are ignored by jidoujisho. The schema change (added fields) forces a
  full sync — sync the phone before/after running Enrich.

---

## 5. Mutation, fields, scope

**Scope:** all in-scope Kinomoto notes — `note:"jidoujisho Kinomoto" -deck:*Core2.3k* -deck:*文法*`.
Every in-scope note is (re)enriched; `Meaning` is overwritten.

**Two new template-unreferenced fields** added to the Kinomoto notetype on first run:
- `Original Meaning` — the pre-enrichment `Meaning`, stashed **once** (guarded by the
  `ankialign::enriched` tag) so re-runs never clobber the true original.
- `Definition Source` — chosen J-J `dict_id` (`三省堂`/`広辞苑`/`実用`/`大辞泉`), or `JMdict`
  for English-only, or `—` if untouched. Queryable in the browser; drives `data-dict`.

**Per note:**
1. If not yet tagged `ankialign::enriched`: copy current `Meaning` → `Original Meaning`.
2. Look up (§3). Build new `Meaning` (§4). Set `Definition Source`. Tag `ankialign::enriched`.
3. **No hit anywhere:** leave `Meaning` untouched. Drop policy: if the dry-run finds
   **fewer than 20** no-hit cards, delete them (shown in the dry-run before confirm);
   if ≥ 20, tag `ankialign::no-definition` instead of mass-deleting. (Currently 0 such cards.)

**Idempotent:** the new `Meaning` is fully determined by `(Term, Reading)` + `defs.sqlite`.
Re-runs recompute and write **only changed** notes; `Original Meaning` is set once.

---

## 6. Error handling & safety

- **Forced backup** via `convert.backup_collection` before any write; rollback = that backup
  (not Ctrl+Z — the schema change clears the undo queue, as documented in `convert.py`).
- **Dry-run preview dialog** before mutating: counts (will-enrich / english-only / no-hit→drop)
  + a few before/after `Meaning` samples + a Backup&&Enrich confirm button.
- **Missing `defs.sqlite`** → dialog reports it and points to `tools/build_def_index.py`; no partial run.
- **Per-note errors** collected and surfaced in the preview/result (like `convert.build_plan`),
  not fatal to the whole run.

---

## 7. Testing

Mirrors the existing pure-module test style (`tests/test_markup.py`, `test_convert.py`):

- **`tests/test_render_defs.py` (pure):**
  - `numbered_glosses`: ordering by `sense_ord`, cap at 4, `1./2.` formatting, single-sense, empty.
  - `choose_jj`: chain first-hit order; reading disambiguation; `reading==''` fallback; no-hit → None.
  - `assemble_meaning`: J-J + J-E block structure; English-only (no `aa-jj`); empty inputs.
  - JMdict gloss extraction from sample Yomitan structured content fixtures (incl. references/refGlosses skipped).
- **`tests/test_enrich.py` (fixture collection):**
  - `Original Meaning` stashed once; re-run doesn't re-stash or rewrite unchanged notes (idempotency).
  - `Definition Source` set correctly per case.
  - no-hit drop-vs-tag threshold (<20 drops, ≥20 tags) on a synthetic set.
- **`tools/build_def_index.py`:** smoke test on a tiny dict fixture → expected rows/index.

---

## 8. Out of scope (separate work)

- Goal #2 pitch-accent fill (`research/pitch-pipeline/`, `pitch-dict-choice` memory).
- Goal #3 word-audio backfill (`local-audio-yomichan-setup` memory).
- Optional one-time LLM gloss rewrite (`research/definition-cleanup/report-llm.md`).
- The `広辞苑→三省堂` resemblance CSS is *part* of this feature but iterated visually after the
  data pipeline lands; the pipeline does not depend on it.
