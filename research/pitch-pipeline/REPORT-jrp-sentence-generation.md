# JRP sentence markup — extension vs. own generator (build-vs-buy)

Date: 2026-06-04. Source: collection measurement + anki-jrp docs/source + web + Discord
(TheMoeWay / KanjiEater). Decides goal #2's *sentence* half: backfill `{unit;accent}` JRP
markup into Sentence fields that lack it. **Companion to** `REPORT-pitch-dicts.md` (dict choice)
and `BRIEF.md` (pipeline design). Term-level pitch (the word's SVG graph) is a separate, already-
built feature — see `HANDOFF-pitch-graph-backfill.md`.

## What "JRP syntax" is here, and what's already solved

"JRP" = the **anki-jrp** add-on (Ben-Kerman/anki-jrp), the de-facto community tool for furigana +
pitch. Its **default syntax** is exactly what our cards store and render:

- readings: `[漢字|かな]`
- pitched word: `{[受|う]け[入|い]れる;Y4,0}` — accents after `;` (downstep mora, `0` = 平板,
  `Y` = 用言/yougen so the renderer applies the conjugation shift, `!` = ambiguous, `,` = multiple).

Two halves of the stack are **already done in this repo and don't need rebuilding**:

1. **Rendering** — the Kinomoto template embeds the JRP "managed section" JS + `<div id="sentence"
   data-jrp-generate>`. `data-jrp-generate` is the add-on's render hook: the JS turns *stored*
   `{unit;accent}` markup into ruby + pitch graphs at display time. It does **not** generate accents
   from plain text at runtime (no MeCab/dict in the template JS). So a plain sentence renders flat —
   which is exactly why 1,751 sentences show no pitch. **We only need to fill the codes into the field.**
2. **Reading** — `ankialign/markup.py` already *parses* this exact syntax (`jrp_to_furigana`,
   `jrp_surface`, `jrp_reading`), validated against the user's gold.

So the question is narrowly: **what writes the `{unit;accent}` string into the Sentence field?**

## The gap (measured against the real collection, 2026-06-04)

| note type | non-empty Sentence | already has `{…;…}` | **needs fill** |
|---|--:|--:|--:|
| jidoujisho Kinomoto | 1,841 | 90 | **1,751** |
| jidoujisho Yuuna | 135 | 135 | 0 |

Matches `BRIEF.md` (1,751 Kinomoto + 29 MyNote plain sentences). The ~31k already-`{…}` blocks
across all note types are 100% pitched — **leave untouched**.

## Option A — use the anki-jrp add-on's bulk converter (buy)

anki-jrp ships a **Notes → bulk convert** action: select notes (same note type), pick the target
field, click Convert. It tokenizes with **MeCab + ipadic** and emits the default syntax, handling
conjugation/kifuku (起伏) — Discord code-readers confirm kifuku is handled well — and inserting `!`
ambiguity marks. It skips/keeps fields that already contain the target syntax (idempotent-ish).

**Pros:** mature, zero build, correct output syntax by construction, handles the hard conjugation
math, bundled MeCab (Windows) / ipadic.

**Cons (data-backed):**
- **Lower coverage on *this* collection.** anki-jrp's accent data is **NHK + Kanjium (~230k
  entries)**. Our own benchmark against the user's mined gold (`REPORT-pitch-dicts.md`): Kanjium 2 =
  86.3%, NHK 1998 = 85.4%, NHK 2016 = 80.3% — vs **アクセント辞典v2 = 97.5%** (the dict we already
  ship an index for). Adopting anki-jrp means **~12-17 pts less coverage** than the path we've
  already validated and built data for.
- **Weaker tokenizer** — MeCab+ipadic vs the SudachiPy + JMdict-lemma pipeline already validated at
  97.6% exact (expr,reading) on these exact decks (`BRIEF.md`).
- **Second accent authority** — would visually disagree with the term-level SVGs (which use
  アクセント辞典v2), reintroducing the Pitch-source mismatch already logged in the ecosystem handoff.
- **GUI-only ritual** — browser-select → menu; not part of AnkiAlign's headless Tools-menu backfill
  flow; not re-runnable inside the pipeline after each unify.
- **Doesn't bundle cloze** — `BRIEF.md`'s plan fills pitch **and** Cloze Before/Inside/After from one
  parse (makes the ~19% conjugated-cloze targets free). anki-jrp does pitch only.
- **Linux setup pitfall** — user is on Linux; MeCab is not bundled (the `Mecab error: invalid line`
  failure shows up in Discord). The ipadic distribution variant mitigates but still needs MeCab.

## Option B — own generator (build), reusing what we already have

Reframe: this is **not** "write our own parser" — the parser exists, the renderer exists, the
dictionary index exists. The missing piece is a **generator** matching `BRIEF.md`:

```
tokenize (SudachiPy + JMdict lemma) → per-word reading/furigana
  → accent lookup (アクセント辞典v2 index, already shipped: 307k keys)
  → conjugation/yougen shift → emit "{unit;accent}" → write Sentence (+ cloze fields)
```

It slots into the **exact pattern already shipped** for term pitch (`pitch.py` engine + dry-run
dialog + forced backup + idempotent Tools-menu action). Pure/impure boundary already established.

**Pros:** 98.1% measured coverage (アクセント辞典v2 + NHK2016), consistent with term SVGs, idempotent
& headless, bundles cloze, re-runs after each unify, no MeCab/GUI dependency.

**Cons:** real engineering. The hard parts anki-jrp already solved: conjugated-form → dict-form
accent + 用言 `;Y` shift, devoicing, `!` ambiguity, segmentation edge cases (counters, proper nouns,
kana-only). Plus the open dependency question: **SudachiPy inside Anki's bundled Python** (footprint/
packaging) — flagged in `BRIEF.md`, still unresolved.

## Recommendation — build, but stand on anki-jrp's algorithm

**Write our own generator, feed it our better data, and *port* anki-jrp's hard linguistic logic
rather than reinventing it.** anki-jrp is MIT-licensed Python; this repo already did exactly this
once — the term-pitch feature "ported mora-splitting from the JRP renderer" (commit `d6b2484`). The
conjugation→accent-shift / kifuku / ambiguity code is the part worth lifting; the dictionary and
tokenizer are the parts where our validated choices (アクセント辞典v2 + Sudachi) beat anki-jrp's
defaults outright on this collection.

Net: **don't adopt anki-jrp as the runtime tool** (lower coverage, GUI-bound, second accent source,
no cloze), and **don't write the conjugation math from scratch** (anki-jrp already has it). Build the
generator into the existing backfill pattern, reusing anki-jrp's algorithm + our index + Sudachi.

A defensible **fast interim**: run anki-jrp's bulk convert once to get *some* sentence pitch on the
1,751 now, accepting ~85% coverage and a one-time GUI pass, while the in-house generator is built.
Only worth it if the user wants coverage this week; otherwise skip straight to the generator since
the dict + renderer + parser groundwork is already laid.

## Open decisions for the user
1. **SudachiPy in the add-on** — bundle (wheel + UniDic-lite footprint) vs. sidecar process vs.
   accept MeCab? This is the one true blocker; everything else is settled. (`BRIEF.md` open Q.)
2. **Accuracy tail (~2%)** — dict-only, or add `marine`/OJAD for dict-misses? `REPORT-pitch-dicts.md`
   says the residual is function words/proper nouns, not a dict gap — likely dict-only is fine.
3. **Bundle cloze fill into the same pass?** `BRIEF.md` recommends yes (free win); could also ship
   pitch-only first.
4. **Bare kanji outside `{}` blocks** (~1,500 partially-marked sentences) — fill those too, or only
   the fully-plain 1,751?
5. **Interim anki-jrp pass — yes/no** (coverage-now vs. wait for the consistent in-house fill).

## Sources
- anki-jrp: <https://github.com/Ben-Kerman/anki-jrp> · README, `doc/template-setup.md`, `doc/mecab.md`
- AnkiWeb listing: <https://ankiweb.net/shared/info/1845503807>
- accent data origin (NHK + Kanjium, ~230k): web search + AnkiWeb distribution notes
- Discord (TheMoeWay 617136488840429598): kifuku-handling confirmation (haonoke 2024-12-22),
  bulk readings+pitch recommendations (Renji-xD 2022-03-08, DJ_Ddawg 2023-10), `data-jrp-generate`
  / JPMN interplay (beaming 2023-10-10), `Mecab error: invalid line` setup pitfall (furretar 2024-03-24)
- In-repo: `REPORT-pitch-dicts.md`, `BRIEF.md`, `HANDOFF-pitch-graph-backfill.md`, `ankialign/markup.py`
