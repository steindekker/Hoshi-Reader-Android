# Pitch dictionary comparison — best combo for filling AnkiAlign sentences

**Method.** Validated each candidate against the collection's own gold: the **10,966 unique
`{unit;accent}` jrp blocks** (36,562 tokens) the user already mined+accepted via jidoujisho
(MyNote/Yuuna/Kinomoto). Ported the JRP parser out of the Kinomoto card template to extract
`(dict-form expression, dict-form reading, accepted accents, is_yougen)` per unit. Each pitch
dict keyed as `(expression, reading) → {downstep positions}`. Coverage = share of gold keys the
dict contains; agreement = of those, share where the dict's positions intersect the user's
accepted accents. Source pack: `Pitch-Accent-…001.zip` (TMW Drive folder, 12 dicts).

Two of the 12 are **term-banks, not pitch term-meta** (`NHK日本語発音アクセント新辞典`,
`Counters & Suffixes`) — pitch is embedded in glossary HTML, not `(expr,reading)→position`.
Excluded from the lookup test; see note below. The other **10 are pitch-format** and compared head-to-head.

## Results (type-level over 10,966 keys; token = freq-weighted over 36,562)

| dict | keys | cover% | agree% | tokCover% |
|---|--:|--:|--:|--:|
| **アクセント辞典v2** | 327k | **97.5** | 100.0 | **97.5** |
| Kanjium 2 | 124k | 86.3 | 99.8 | 84.9 |
| NHK 1998 | 94k | 85.4 | 99.9 | 88.9 |
| 三省堂第八版 | 77k | 80.6 | 99.3 | 85.1 |
| NHK 2016 (Recommended) | 72k | 80.3 | 99.8 | 86.0 |
| 大辞林第四版 | 151k | 76.3 | 99.7 | 76.8 |
| 大辞泉 (Recommended) | 86k | 74.8 | 99.4 | 75.2 |
| 新明解第八版 | 76k | 70.7 | 99.8 | 72.4 |
| 新明解第七版 | 72k | 70.5 | 99.8 | 72.5 |
| kotu_pitch | 120k | 65.6 | 99.3 | 73.4 |

**Agreement is not a discriminator.** Cross-dict disagreement on shared keys is uniformly tiny
(0.05–1.2%; NHK-1998↔NHK-2016 = 0.05%, same lineage). Standard 標準語 pitch is broadly agreed, so
the decision variable is **coverage**, not correctness. Ruled out the obvious confound: アクセント辞典v2's
~100% agreement is **not** from listing many accents per word — its avg positions/key is 1.115,
*lower* than NHK (1.16) or 新明解 (1.19). It is genuinely both comprehensive and accurate.

## Best combo

Greedy incremental coverage:

```
+ アクセント辞典v2          97.5%   (alone)
+ NHK 2016               98.1%   (+0.6, biggest single increment of any 2nd dict)
+ Kanjium 2              98.4%
+ kotu_pitch             98.6%
… everything else        <0.3% each — redundant
```

### Verdict
- **Primary: アクセント辞典v2.** Covers 97.5% of *your actual mined vocabulary* at accuracy
  indistinguishable from NHK. It is a near-superset of every other dict in the pack.
- **Fallback: NHK 2016 (Recommended).** Adds the most incremental coverage (→98.1%) and is the
  TMW-authoritative source — good as the trusted cross-check / display label. In Yomitan, order it
  **after** アクセント辞典v2 (first dict with the term wins).
- **Stop at two.** Kanjium / 大辞泉 / 大辞林 / 新明解 ×2 / 三省堂 / NHK 1998 / kotu are redundant for
  *filling* (each <0.3% marginal). Keep them out of the pipeline.

This is slightly different from the TMW chat advice (NHK2016 + 大辞泉) — that ranking optimizes
**curation/authority**, but for raw *fill coverage* on this collection アクセント辞典v2 wins outright,
and its 0.45% disagreement vs NHK shows it costs nothing in accuracy.

## The residual ~2% is not a dictionary problem
210 types / 541 tokens missed by アクセント辞典v2 ∪ NHK2016. Breakdown of the top misses:
- **Irregular-verb dict-form normalization** (来て/来た/来ます→くる, しれる, みられる) — these are
  *harness artifacts*: gold keeps the surface kanji (来て) while the dict has 来る. The tokenizer's
  dict-form lemma fixes these, so true dict-only coverage is **higher** than 97.5%.
- **Grammatical particles / aux** (の ×53, ちゃう ×42, ないん, なんか) — function words absent from
  pitch dicts by design.
- **Plural compounds** (-たち: 子供たち, 俺たち) and **proper nouns** (バイデン).

Yougen coverage (the hard conjugation case) holds up: アクセント辞典v2 = **95.9%**, +NHK2016 = 97.0%
(non-yougen 97.9 → 98.4). Closing the last ~2% needs the **tokenizer dict-form output + an optional
neural/OJAD tail** (BRIEF tests 2–3), not more dictionaries.

## Note on NHK日本語発音アクセント新辞典 (term-bank)
This is the newest NHK accent dictionary but packaged as a full term dictionary (pitch in HTML
glossary). Usable as a *reading/reference* dict in Yomitan, but not as a structured pitch source
for automated filling without an HTML-accent extractor. Not needed given アクセント辞典v2 + NHK 2016.

---
*Reproduce: `python3 work/validate.py` (dicts extracted under `dicts/d/`).*
