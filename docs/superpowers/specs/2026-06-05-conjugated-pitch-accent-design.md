# Conjugated-pitch-accent computation — design spec

Status: **DRAFT for approval** (2026-06-05, branch `feat/jrp-sentence-fill`)
Owner: jrp_fill generator

## 1. Problem

Conjugated 用言 currently render their **inline** coloring correctly (kifuku/amber)
but the **hover pitch graph** shows the *dictionary-form* contour, not the actual
conjugated one:

```
断られた   inline = kifuku ✓     hover graph = ことわ\る   (want: ことわら\れた)
```

This is because we emit `{[断|ことわ]られた;Y3|ことわる}` — the `|ことわる` base makes
the renderer compute the graph from the lemma. anki-jrp's gold does the same
(its `[いた=く]` inline-base form), so this is a known shared limitation, not a
regression.

User decision (2026-06-05): **compute the real conjugated downstep** so the graph
matches the spoken form. Scope is the hover graph only; inline coloring is already
right.

## 2. Why this is from-scratch (feasibility findings)

- **No port exists.** anki-jrp (`src/pylib/converter.py`) *joins* the verb + aux
  chain and looks up the **dictionary-form** accent with a base reading — it never
  shifts the downstep. perapera's `deinflect.py` has no accent code. Our own
  `d6b2484` was only mora-splitting.
- **No lookup path.** Source dicts (アクセント辞典v2, NHK2016) are
  `[term,"pitch",{reading,pitches:[{position}]}]` — dictionary-form headwords only.
  No conjugated entries.
- **No bulk ground truth.** The 90 gold Sentence cards show dict-form pitch too,
  so they can't validate conjugated output. Validation must be a hand-curated set
  checked against an external authority (OJAD / NHK新辞典 appendix).

Conclusion: implement the 用言 **accent-combination rules** (結合規則) ourselves and
validate against a curated set. Medium-large, long-tail accuracy risk.

## 3. What we have to work with (inputs)

Per Sudachi morpheme we already get everything the rules need:

- head 用言: conjugation **type** `pos[4]` (五段-ラ行, 下一段-バ行, カ行変格, サ行変格,
  上一段, 形容詞…) and **form** `pos[5]` (未然形, 連用形, 連用形-促音便, 仮定形,
  意志推量形, 終止形…), plus `dictionary_form` and reading.
- the auxiliary chain, each tagged 助動詞-{タ,ナイ,マス,タイ,レル,ウ,ヌ…} or
  形容詞/非自立可能 (ない), 助詞/接続助詞 (て/で/ば).
- **accent class** from our dict: lemma accent `0` ⇒ 平板, `>0` ⇒ 起伏 (dict-form
  nucleus on the penultimate mora).
- conjugated **reading** (mora sequence) for placing the nucleus.

So the engine is a pure function:

```
conj_accent(class: heiban|kifuku, chain: list[Aux], moras: list[str]) -> int   # 0..len
```

## 4. Algorithm

### 4.1 Accent class, not raw number
Collapse the dict accent to a class: `kifuku = (dict_accent != 0)`. The dict
*number* is not reused — for accented 用言 the conjugated nucleus is determined by
the **ending**, not the dictionary position. (Verbs/adjectives are a two-way
accent system; this is the standard simplification OJAD uses.)

### 4.2 Per-ending rule table (the core work)
Each ending/auxiliary, given the incoming class, yields (a) the resulting class
and (b) where the nucleus lands, expressed relative to the form. The chain is
folded left-to-right: head form → aux → aux …, each step a small rule. Examples
of the rule *shape* (exact placements to be encoded from NHK新辞典 appendix
「動詞・形容詞の活用とアクセント」 and spot-checked on OJAD — NOT from memory):

| ending / aux        | heiban 用言            | kifuku 用言                      |
|---------------------|------------------------|----------------------------------|
| 終止/連体 (dict)     | 0                      | penult                           |
| ます (polite)        | nucleus on ま          | nucleus on ま                    |
| た/だ, て/で          | 0                      | nucleus before た/て              |
| ない (negative)      | (per authority)        | nucleus before ない               |
| ば (conditional)     | …                      | …                                |
| volitional よう/おう  | …                      | …                                |
| 受身/使役 れる/られる/せる | treated as a fresh 下一段 kifuku stem, recurse |
| たい (desiderative)  | behaves like an i-adj, recurse |

Causative/passive/desiderative **restart** the recursion: e.g. 食べ+させ+られ+た
is 食べる→(causative させる, a 下一段 verb)→(passive られる)→(past た); each layer
applies its rule to the running result. This makes long chains
(`食べさせられたくなかった`) fall out of the same fold.

### 4.3 Output
Emit the computed nucleus on the **conjugated reading**, keep `Y` for kifuku
coloring, and **drop the `|base`**:

```
{[断|ことわ]られた;Y4}          # 4 = downstep after ら on ことわられた
{[分|わ]からなかった;Y?}         # computed on わからなかった
```

`;Y0` stays heiban (flat). Where the rules are uncertain (see §6) we fall back to
the current `;Y<dictacc>|base` form so we never emit a *wrong* number — degrade to
"dict-form graph", never to "wrong graph".

## 5. Integration

- New pure module `ankialign/jrp_fill/conjugate.py` — `conj_accent(...)` + the rule
  tables + the mora helper (reuse `pitch`/`_utils`). No Sudachi/Anki imports →
  unit-testable in isolation.
- `tokenize._merge_yougen` already collects the head + tail morphemes; pass their
  (type, form, aux-tag) + class + conjugated moras to `conj_accent`. On success
  store the computed accent and mark the Unit so emit drops the base; on
  `None`/uncertain keep today's behaviour.
- `emit.py`: when a yougen Unit carries a *computed* conjugated accent, emit
  `;Y<acc>` with no base; otherwise unchanged.
- Lexicalized-whole-form path (思わず) and dict-form verbs (届く) are untouched.

## 6. Validation (no bulk gold — this is the risk)

- **Curated test set**: ~8 conjugation forms × {heiban, kifuku} × {godan,
  ichidan, suru, kuru, i-adj} ≈ 80–120 cases, each accent hand-verified on OJAD.
  Lives in `tests/data/conj_accent_cases.tsv`. This is the regression oracle.
- **Spot diff vs gold**: regenerate the 90 gold cards; the *surfaces* must still
  round-trip and inline colors match — only graphs change.
- **Coverage gate unchanged**: the ≥98% strict (lemma,reading) accent coverage
  gate must not drop (the engine only re-places an already-found accent).
- **Conservative fallback**: any (type, form, aux) combo not in the table →
  fall back to dict-form base (current behaviour), logged, so we can grow the
  table from real misses.

## 7. Scope / non-goals

- In: the common productive conjugations of 動詞 (五段/一段/する/来る) and i-adjectives,
  including stacked 受身/使役/たい/ない/た/て/ます/ば/volitional.
- Out: な-adjectives (not 用言 pitch), rare/literary auxiliaries (ぬ outside ません,
  り, つ…), dialectal accents, emphatic/compound shifts. These fall back.
- Out: changing inline coloring (already correct) or the 1,742-fill surfaces.

## 8. Rollout

1. `conjugate.py` + curated test set (red).
2. Encode rules form-by-form, highest frequency first (た/て/ます/ない), green.
3. Wire into tokenize/emit behind a flag `--conj-accent` (default off) for A/B in
   the preview.
4. Regenerate preview (existing mode) → eyeball vs gold on real cards.
5. Flip default on once the curated set passes and the preview looks right.

## 9. Open questions

- Authority for exact nucleus placement: NHK新辞典 appendix vs OJAD — pick one and
  cite per rule. (OJAD is the easiest to spot-check.)
- Do we want the engine for **partial** sentences too, or only the plain fills?
  (Recommend: same code path, so both.)
