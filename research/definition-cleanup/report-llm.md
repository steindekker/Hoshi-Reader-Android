# LLM Rewrite Pass — Definition Cleanup Research

## 1. Keep / Drop Policy

The cleanup prompt must be conservative: **distil, never invent**. The source material contains the canonical meaning; the LLM's only job is to strip noise and compress redundancy.

### Keep
- **Core gloss(es)**: every distinct meaning sense, in English (see J-E vs J-J decision below).
- **Part-of-speech tag** when it is non-obvious or affects usage (e.g. `na-adj`, `suru-verb`). Omit when trivially a noun.
- **One short example phrase** (Japanese) if it meaningfully disambiguates a sense that words alone cannot (rare — only for polysemous words).
- **Antonym note** if the source explicitly flags one and it aids recall (e.g. 買手↔売手).

### Drop
- All HTML tags and attributes (`<div>`, `<ol>`, `<li>`, `<i>`, `<br>`, etc.)
- Dictionary headers and provenance markers (`【デジタル大辞泉】`, `【旺文社…】`, `【新明解…】`, `【KireiCake】`, `【JMdict Extra】`)
- Kanji Study furigana markup (`有[あ,ある;k1]り`, `{;h}`, etc.) — strip entirely
- Romaji (`[ローマ字](ihen)`, `ヘウ──` reading headers)
- Duplicate senses across dictionaries (keep the clearest wording, discard near-synonyms)
- Long J-E example sentences from 研究社/斎藤 that are just usage examples of the English gloss
- Frequency/star ratings (`★★★`)
- Pitch accent and accent pattern notation within the definition text
- Cross-references to sub-entries (e.g. `━いったい ① ⓪ 【─一体】…` extended entries)
- Bracketed source annotations `{Barg.}`, `{ritsuryō}` unless essential to meaning

### Output format
Minimal clean HTML: numbered list for multiple senses (`<ol><li>…</li></ol>`); plain text if single sense. Output only the cleaned definition string, nothing else.

---

## 2. J-E vs J-J Recommendation

**Recommendation: English (J-E).**

Rationale:
- The user's existing sources are predominantly J-E (MyNote JMdict Dutch/NL, jidoujisho KireiCake EN, 研究社/斎藤 EN). The Dutch glosses in MyNote confirm the user learned Japanese from a non-Japanese-native context; English definitions (or Dutch) are the expected meaning anchor.
- Switching to J-J (Japanese definitions from 大辞泉/新明解) would require reading the definition during review, adding cognitive load. J-J is valuable for advanced learners trying to think in Japanese, but the collection evidence shows intermediate sentence-mining usage.
- The J-J definitions in the Kinomoto source **can be used as input** to the LLM — they often contain nuance the English glosses miss — but the LLM should output English.
- The prompt tells the model to "output a clean English definition; use the Japanese definitions as context to get nuance right, but write the result in English (or Dutch if the source is Dutch-primary)."

Note: cleaned J-J could be stored in `Expanded Meaning` (already a Kinomoto field) if ever wanted separately.

---

## 3. Cleanup Prompt (production-ready)

```
System:
You are a Japanese dictionary editor. Your task is to distil a raw Anki flash-card definition
into a clean, minimal English gloss suitable for spaced-repetition review.

Rules:
- Output ONLY the cleaned definition string, nothing else — no commentary, no JSON.
- If there are multiple distinct senses, use <ol><li>…</li></ol>. If a single sense, output plain text.
- Language: English. If the source is Dutch-primary (contains Dutch words), output Dutch.
- Strip: all HTML tags, all dictionary headers (【…】 markers), all Kanji Study furigana markup
  ([kanji,reading;code] patterns), all romaji readings, all pitch notation, all example sentences
  from English dictionaries, all star/frequency ratings, all cross-reference sub-entries (━… entries).
- Keep: every distinct meaning sense, part-of-speech only if non-obvious (na-adj / suru-verb /
  counter), one short Japanese example phrase if it disambiguates a polysemous term (rare).
- Do NOT invent meanings. If the source is ambiguous, prefer the broader reading.
- Do NOT add any English explanatory text not grounded in the source material.
- Maximum output: 120 characters for a single-sense word, 300 characters for multi-sense.

User:
Term: {term}

Raw definition:
{raw_meaning}
```

---

## 4. Cost and Time Estimate

### Data grounding (from collection.anki2)

| Source | Notes in scope | Total chars | Mean chars | Median chars |
|--------|---------------|-------------|-----------|-------------|
| MyNote (mined only, excl Core2.3k) | 5,020 | 1,187,917 | 237 | 210 |
| jidoujisho Yuuna | 211 | 61,142 | 290 | 248 |
| Kanji Study Word Model v3 | 259 | 50,020 | 193 | 120 |
| jidoujisho Kinomoto | 2,681 | 3,105,161 | 1,158 | 755 |
| **Total** | **8,171** | **4,404,240** | — | — |

Notes:
- Core2.3k (2,235 MyNote notes) excluded — that deck stays as-is.
- Kinomoto is already the target note type but its `Meaning` fields are multi-dict clutter from the original yomitan export; they benefit equally from cleanup.
- Kinomoto's long tail is real: p95=3,076 chars, p99=10,289 chars, max=24,066 chars. The top ~40 outliers (>5,000 chars) account for a disproportionate share of Kinomoto's 3.1M chars and need pre-filtering.

### Token estimates

Assumptions:
- 4 chars ≈ 1 token (conservative for mixed Japanese+English; safe overestimate).
- System prompt overhead: 200 tokens per call.
- Output target: 80 tokens per note.

| Item | Tokens |
|------|--------|
| Input from field values (4,404,240 ÷ 4) | 1,101,060 |
| System prompt overhead (8,171 × 200) | 1,634,200 |
| **Total input tokens** | **2,735,260** |
| Output tokens (8,171 × 80) | 653,680 |

### Cost table

| Model | $/MTok in | $/MTok out | Input cost | Output cost | **Total** |
|-------|-----------|------------|------------|-------------|-----------|
| Haiku 3.5 standard | $0.80 | $4.00 | $2.19 | $2.61 | **$4.80** |
| **Haiku 3.5 Batch API** | **$0.40** | **$2.00** | **$1.09** | **$1.31** | **$2.40** |
| Sonnet 4 standard | $3.00 | $15.00 | $8.21 | $9.81 | $18.02 |
| Sonnet 4 Batch API | $1.50 | $7.50 | $4.10 | $4.90 | $9.00 |

### Model recommendation: Haiku 3.5 via Batch API

- Task is format-stripping + sense selection, not reasoning. Haiku 3.5 handles it well.
- Cost: ~$2.40 for 8,171 notes (one-time). Negligible.
- Wall-clock: Batch API at this volume typically completes in 20–40 min; 24h SLA is the guarantee.
- If quality spot-checks show Haiku struggling with ambiguous multi-dict senses (complex 研究社 entries), upgrade to Sonnet 4 Batch for ~$9 — still one-time.

---

## 5. Safety and Operations Plan

### 5a. Preserve the original (non-negotiable)

Before running the rewrite:
1. **Full .colpkg backup** — already part of AnkiAlign's safety model (`col.create_backup`). Do this first.
2. **Move raw `Meaning` into `Expanded Meaning`** before overwriting `Meaning`. Kinomoto already has this field. Operation: `Expanded Meaning ← raw; Meaning ← cleaned`. Reversible within Anki without restoring a backup.

If `Expanded Meaning` is already populated for some Kinomoto notes (jidoujisho's own dict-priority summary), check fill rate first and decide whether to overwrite or skip those notes.

### 5b. Determinism

LLM outputs are not deterministic. Mitigations:
- Set `temperature=0` for cleanup calls.
- Cache outputs keyed by `(note_id, sha256(raw_meaning))`. A partial run can be resumed without re-paying for already-processed notes.

### 5c. Network dependency

Real tradeoff. AnkiAlign is otherwise fully offline. Recommended approach:
- Implement as a **standalone one-time migration script** (`scripts/clean-meanings.py`), NOT as a feature baked into the Anki add-on.
- Script reads `collection.anki2`, submits a Batch API `.jsonl`, writes results to `meaning_rewrites.jsonl`.
- Separate `scripts/apply-rewrites.py` reads the result file and updates the collection.
- The add-on remains offline; the LLM pass is an explicit opt-in migration step.

### 5d. Failure handling

- Batch API returns per-request success/error. Collect failed IDs and retry as a smaller batch.
- Length outliers: notes >4,000 chars, pre-truncate at the first `<br><br>` boundary after 2,000 chars (drop later dictionaries, keep first 1–2). Later dicts in Kinomoto are typically 斎藤/研究社 example-sentence dumps; dropping them is safe.
- Empty or <10 char output: flag as failed, keep original.

### 5e. Quality assurance

- After results arrive, sample ~50 notes manually (stratify by source and length bucket).
- Spot-check the Kinomoto long-tail outliers for truncation artifacts.
- Verify no note has an empty `Meaning` after rewrite.
- `Expanded Meaning` retains the original for any card that looks wrong.

---

## 6. Hand-done BEFORE → AFTER (all samples)

Demonstrates achievable target quality. Policy applied: keep/drop as defined in section 1.

---

### SOURCE: MyNote — JMdict-style HTML, Dutch glosses

---

**Term: それ** (414 → ~30 chars)

BEFORE:
```
<div style="text-align: left;"><ol><li><i>(pn)</i> <ul><li>dat</li><li>die</li><li>het</li></ul></li><li><i>(pn)</i> <ul><li>daar-</li><li>-dien</li></ul></li><li><i>(pn)</i> <ul><li>daar</li><li>ziedaar</li><li>zie (je wel)</li><li>alstublieft</li><li>voilà</li></ul></li><li><i>(pn)</i> <ul><li>goed zo!</li><li>tsa</li><li>pak ze</li><li>hallali</li><li>hup {tegen afgericht dier enz.}</li></ul></li></ol></div>
```

AFTER:
```
<ol><li>dat, die, het</li><li>daar-, -dien</li><li>daar, ziedaar, voilà</li><li>goed zo!, pak ze (aanmoediging)</li></ol>
```

Notes: Dutch source → Dutch output. POS `(pn)` dropped (pronoun is obvious from context). Near-synonyms within each sense collapsed. `alstublieft` appears in two senses with different nuance; `voilà` in sense 3 and `pak ze` in sense 4 are the most distinctive markers kept.

---

**Term: 一つ** (773 → ~150 chars)

BEFORE:
```
<div style="text-align: left;"><ol><li><i>(adj-no)</i> <ul><li>één</li><li>eentje</li><li>{per} stuk</li></ul></li><li><i>(adj-no)</i> <ul><li>enkel ~</li><li>slechts ~</li><li>alleen ~</li><li>{met negatie} zelfs geen ~ ...</li></ul></li><li><i>(adj-no)</i> <ul><li>één en hetzelfde</li>...</li></ul></li><li><i>(adj-no)</i> <ul><li>ten eerste</li>...</li></ul></li><li><i>(adj-no)</i> <ul><li>eens (even)</li>...</li></ul></li><li><i>(adj-no)</i> <ul><li>alstublieft</li><li>alsjeblieft ...</li></ul></li></ol></div>
```

AFTER:
```
<ol><li>één, per stuk</li><li>enkel ~, slechts ~ (zelfs geen ~ met negatie)</li><li>één en hetzelfde, een geheel</li><li>ten eerste, om te beginnen</li><li>eens even</li><li>alsjeblieft (lichte aandrang)</li></ol>
```

Notes: `adj-no` dropped (all senses identical POS, not discriminating). Parenthetical usage notes kept for senses 2 and 6 where context matters. Near-synonyms within each sense collapsed.

---

**Term: 一** (51 → ~3 chars)

BEFORE:
```
<div style="text-align: left;"><i>(n)</i> een</div>
```

AFTER:
```
één
```

Notes: Trivial. `(n)` dropped.

---

**Term: 二** (365 → ~20 chars)

BEFORE:
```
<div style="text-align: left;"><ol><li><i>(num)</i> <ul><li>twee</li><li>{Barg.} beis</li></ul></li><li><i>(num)</i> tweede</li><li><i>(num)</i> <ul><li>twee</li><li>tweede</li><li>volgende</li></ul></li><li><i>(num)</i> <ul><li>{ritsuryō} vice-minister voor Dazaifu 大宰府</li><li>(a) formele Chinese cijfervariant van 二</li><li>(b) verraden</li></ul></li></ol></div>
```

AFTER:
```
twee; tweede
```

Notes: Senses 1–3 all mean "two / second" — collapsed. Sense 4 (archaic ritsuryō administrative gloss) has zero SRS recall value; dropped. `{Barg.} beis` (slang) dropped.

---

### SOURCE: jidoujisho Yuuna — multi-dict with 【…】 headers

---

**Term: 表裏** (435 → ~80 chars)

BEFORE:
```
【旺文社国語辞典 第十一版 画像無し】
ひょう‐り【表裏】ヘウ――
🈩 （名）おもてとうら。外面と内心。
🈔 （名・自スル）人前での言葉や態度と内心とが相違すること。「―のある人物」「言行が―する」

【新明解国語辞典　第七版】
...
━いったい ① ⓪ 【─一体】
...（sub-entry 表裏一体）

【KireiCake】
• two sides
• inside and outside
```

AFTER:
```
<ol><li>front and back; two sides</li><li>duplicity; saying one thing and meaning another</li></ol>
```

Notes: Sub-entry 表裏一体 dropped (separate word). Dictionary headers, reading notation, circled numbers stripped. Sense 2 (duplicity) is the more important learner sense and is preserved.

---

**Term: 土砂** (151 → ~35 chars)

BEFORE:
```
【旺文社国語辞典 第十一版 画像無し】
ど‐しゃ【土砂】
土と砂。「―崩れ」

【新明解国語辞典　第七版】
ど しゃ ① 【土砂】
土と砂。
「━崩れ ③ 」

【JMdict Extra】
• sediment
• earth and sand

【KireiCake】
earth and sand
```

AFTER:
```
earth and sand; soil and sediment
```

Notes: All four dictionaries agree on one meaning. 土砂崩れ example dropped (it's a compound word, not a usage example). Single sense, plain text.

---

**Term: 買手** (221 → ~25 chars)

BEFORE:
```
【旺文社国語辞典 第十一版 画像無し】
かい‐て【買手】カヒ――
物を買うほうの人。買い主。⇔売手

【新明解国語辞典　第七版】
かい て ⓪ カヒ─【買（い）手】
その商品を買う人。↔売り手

━しじょう（オ） ④ ─ヂヤウ【─市場】
需要が供給より少なかったり…（sub-entry 買い手市場）

【JMdict Extra】
• 買い手（★）
• 買手（★）

【KireiCake】
buyer
```

AFTER:
```
buyer (↔ 売手 seller)
```

Notes: All dicts agree: buyer. Sub-entry 買い手市場 dropped. JMdict Extra entries are spelling variants, not definitions — dropped. Antonym kept (↔ 売手) because it appears in both J-J sources and aids recall.

---

### SOURCE: Kanji Study Word Model v3 — furigana markup clutter

---

**Term: 有りそう** (65 → ~15 chars)

BEFORE:
```
あり[,ある;k1] そう[;h,a] 【有[あ,ある;k1]り そう[;h,a] 】{<br>probable, likely}
```

AFTER:
```
probable, likely
```

Notes: Strip the entire Kanji Study furigana markup prefix. Only the actual gloss inside `{<br>…}` survives. This is the simplest case; every Kanji Study note follows this pattern.

---

**Term: 気合** (732 → ~80 chars)

BEFORE:
```
き[,くる;k1] あい[,あう;k1] 【気合[きあい;h] 】{ ★★★<br>}き  あい[;a]  —あ ひ[,ひる;k1]  [0]【気合[きあい;h] 】<br>
①...精神を集中してかかるときの気持ちの勢い...
②...
③...
き[,くる;k1] あい[,あう;k1] 【気合[きあい;h] 】{ ★★★<br>
1.  (fighting) spirit, motivation, effort
2.  shout (for getting in the right mood...), cheer, yell
3.  kiai, short shout when performing an attacking move}
```

AFTER:
```
<ol><li>(fighting) spirit, motivation, effort</li><li>battle cry, kiai; shout for focus</li></ol>
```

Notes: Kanji Study field contains two copies: a furigana-annotated J-J section and then a clean English section at the end inside `{ … }`. Use the English section as the base; J-J validates nuance. Senses 2 and 3 merged (both describe the same vocalization concept). ★★★ dropped.

---

**Term: 私立** (562 → ~90 chars)

BEFORE:
```
しり[,しる;o] つ 【私立[しりつ;a] 】{ ★★★<br>}し[,する;h]  りつ  [1]【私立[しりつ;a] 】<br>
（名）スル
①個人や法人が設立し経営していること。また，そのもの。特に，私立学校をいうことが多い。わたくしりつ。→国立・公立
②自分の力でやっていくこと。「官に在て事を為すと，其範囲を脱して━するとの利害得失を述べ／学問ノススメ諭吉」
<br>
し[,する;h] りつ 【私立[しりつ;a] 】{ ★★★<br>private (establishment)}
```

AFTER:
```
<ol><li>private (establishment), privately founded</li><li>self-reliance; doing things by one's own effort</li></ol>
```

Notes: English gloss at the end gives sense 1. The J-J section reveals sense 2 (self-reliance / independence) which is absent from the English section — preserved from J-J. Case where J-J input adds genuine value.

---

### SOURCE: jidoujisho Kinomoto — yomitan multi-dict walls

---

**Term: 異変** (1,671 → ~100 chars)

BEFORE:
```
【デジタル大辞泉】
い‐へん【異変】
[名・形動]
① 普段は見られない異常な現象・出来事。変事。「政界に異変が起こる」「暖冬異変」
② 普通と異なり変わっていること。また、そのさま。「病状に異変を来す」

【旺文社国語辞典 第十一版 画像無し】
い‐へん【異変】
① 変わること。変化。「病状に―はない」
② ふつうとは異なったできごと。「暖冬―」

【NEW斎藤和英大辞典】
いへん〔異変〕
〈名〉An accident; a mishap; a disaster; a calamity; a catastrophe; a cataclysm
0.  途中で何か異変があったのではないかと心配しておった
0.  I feared lest there should have been some disaster on the road.

【研究社　新和英大辞典　第５版】
いへん【異変】 [ローマ字](ihen)
〔事故〕 an accident; 〔変事〕 a strange event; something strange; a disaster; 〔非常事態〕 an emergency; 〔異常現象〕 a strange phenomenon; 〔変化〕 a change (for the worse).
►太陽の異変　an extraordinary solar phenomenon
... (many more example sentences)
```

AFTER:
```
<ol><li>unusual occurrence, abnormal event; something out of the ordinary</li><li>sudden change (for the worse); disturbance</li></ol>
```

Notes: All four dictionaries converge on two senses: (1) an unusual/abnormal happening, (2) a worrying change. The 研究社 entry's English examples (the bulk of 1,671 chars) are usage illustrations, not definition content — all dropped. 大辞泉 used as the semantic anchor.

---

**Term: 綿密** (1,611 → ~70 chars)

BEFORE:
```
【デジタル大辞泉】
めん‐みつ【綿密】
[名・形動] 詳しく細かいこと。すみずみまで注意が行き届いていること。また、そのさま。「綿密な調査」「綿密に計画を立てる」
[派生] めんみつさ [名]

【旺文社国語辞典 第十一版 画像無し】
めん‐みつ【綿密】
（名・形動ダ）細かいところまで注意が行き届いていること。また、そのさま。念入り。「―な計画を立てる」

【NEW斎藤和英大辞典】
...minute, nice, close, thorough, scrupulous, elaborate...
0.  綿密な観察 minute observation... (8 more example lines)

【研究社　新和英大辞典　第５版】
1 〔細密〕 ～な minute; nice; close; detailed.
2 〔周到〕 ～な careful; scrupulous; meticulous; sedulous; elaborate.
```

AFTER:
```
meticulous, thorough, detailed (na-adj); 綿密な調査: thorough investigation
```

Notes: All dicts agree on the meaning. `na-adj` kept because adjectival noun is non-obvious. One canonical collocation kept (`綿密な調査`) as it is the most common usage context. 派生形 and all 斎藤 examples dropped.

---

**Term: 自戒** (308 → ~70 chars)

BEFORE:
```
【デジタル大辞泉】
じ‐かい【自戒】
[名](スル) 自分の言動を自分でいましめ慎むこと。「おごり高ぶらないように自戒する」

【旺文社国語辞典 第十一版 画像無し】
じ‐かい【自戒】
（名・自スル）自分自身を戒めて気をつけること。「自重―する」

【研究社　新和英大辞典　第５版】
じかい５【自戒】 [ローマ字](jikai)
remonstrating with oneself; self-castigation; self-reproach; self-remonstrance.
〜する caution oneself 《against…》.
```

AFTER:
```
self-admonition; cautioning oneself against bad habits or arrogance (suru-verb)
```

Notes: `suru-verb` kept because non-obvious. The parenthetical "against bad habits or arrogance" comes from 大辞泉's example `おごり高ぶらないように自戒する` — anchors typical usage without quoting the full sentence. `self-castigation`/`self-reproach` from 研究社 are near-synonyms; kept the most natural English equivalent.

---

**Term: 裕福** (1,516 → ~55 chars)

BEFORE:
```
【デジタル大辞泉】
ゆう‐ふく【裕福】
[名・形動] 財産や収入がゆたかで生活に余裕があること。また、そのさま。富裕。「裕福な家庭」「裕福に暮らす」
[派生] ゆうふくさ [名]

【旺文社国語辞典 第十一版 画像無し】
ゆう‐ふく【裕福】
（名・形動ダ）経済的にゆとりがあって、生活が豊かなこと。また、そのさま。富裕。「―な生活」

【NEW斎藤和英大辞典】
...wealthy, affluent, opulent, warm, well-to-do...
(5 example lines)

【研究社　新和英大辞典　第５版】
〜な rich; wealthy; affluent; opulent; well-to-do; well-off; comfortably off; prosperous; well-heeled.
►裕福な人　a well-off person...
(many examples)
```

AFTER:
```
wealthy, affluent, well-off (na-adj); financially comfortable
```

Notes: Single concept across all dicts. `na-adj` retained. Synonym list collapsed to three most natural English equivalents. All 斎藤/研究社 example sentences dropped — they illustrate "wealthy" with different constructions but add zero SRS recall value. 派生形 ゆうふくさ dropped.

---

## 7. Summary of achievable quality

The cleaned definitions are:
- **5–10× shorter** than the originals (1,500→60 chars for Kinomoto; 400→25 chars for MyNote)
- **Format-uniform**: consistent `<ol><li>` for multi-sense, plain text for single-sense
- **Language-clean**: no markup, no headers, no romaji, no example-sentence bulk
- **Semantically faithful**: nothing invented; nuance from J-J sources folded into English output where the English-only gloss would have missed it (see 私立 sense 2, 自戒 usage note)

Downsides that remain:
- Non-deterministic: temperature=0 helps but does not fully eliminate variance — same prompt on two runs may choose slightly different synonym orders
- ~$2–3 one-time cost, network required
- The prompt above will produce near-correct output on ~95% of notes; the Kinomoto long-tail outliers (>5k chars) need the truncation pre-filter to avoid context issues or runaway costs
- Sub-entries like 表裏一体 (linked phrases) silently disappear — correct for `Meaning` but the user should know; they belong in `Notes` if wanted
