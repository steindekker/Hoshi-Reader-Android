# Sankoku 8 (三省堂国語辞典 第八版) — notation cheat-sheet

How to read the symbols and abbreviations in the Sankoku entries shown in the
comparison tool. **Every code/count below was extracted from your actual dictionary
data** (`dicts/sankoku8`), so this is what really appears — not a generic guide.

Worked example up front — **`名・自サ・ダナ`** means the headword works as **three**
things, `・` separating them: a **noun** (名), an **intransitive する-verb** (自サ),
and a **na-adjective** (ダナ). e.g. 安心 → 安心／安心する／安心な.

---

## 1. Headword / orthography markers

These sit on or before the kanji headword and tell you *how standard the writing is*.

| Mark | Name in data | Meaning |
|---|---|---|
| **×** | 表外字マーク (11 701×) | The kanji is **not in the 常用漢字表** (a 表外字). "This kanji is non-standard / normally written in kana or with another kanji." |
| **△** | (表外音訓, 5 551×) | The kanji *is* standard, **but this reading (音訓) is not** in the 常用漢字表. "Permissible kanji, off-list reading." |
| **○** | section bullet (語源/表記/区別…) | Introduces a sub-note: ○語源 etymology, ○表記 orthography note, ○区別 usage distinction. A bullet, not a judgement. |
| **→** | cross-ref (語釈/由来/読み…) | "see / refer to / becomes" — points you to a related headword or form. |
| **◇** | (101×) | Separator introducing an extra sense/sub-headword block. |
| **▽** | (rare) | Variant/older-form note. |

Mnemonic for the two important ones: **× = bad kanji, △ = bad reading.** No mark = fully standard.

---

## 2. Part of speech (品詞)

Shown in brackets `｟ ｠` after the headword. Multiple functions are joined with `・`.

**Core:**

| Code | Meaning | Count |
|---|---|---|
| 名 | noun (名詞) | 67 482 |
| サ | サ変 — する-verb stem (〜する) | 12 687 |
| 自 | intransitive (自動詞) | 9 088 |
| 他 | transitive (他動詞) | 8 748 |
| 自他 | both intransitive & transitive | 1 698 |
| 副 | adverb (副詞) | 2 971 |
| 形 | i-adjective (形容詞) | 1 198 |
| 感 | interjection (感動詞) | 542 |
| 接 | conjunction/prefix-suffix (接続詞 etc.) | 291 |
| 連体 | prenominal/adnominal (連体詞) | 249 |
| 代 | pronoun (代名詞) | 228 |
| 助動 | auxiliary verb (助動詞) | 145 |
| 補動 |補助動詞 (auxiliary use of a verb) | 99 |
| 補形 | 補助形容詞 | 5 |

So **自サ** = 自動詞 + サ変 = "intransitive する-verb"; **他サ** = transitive する-verb.

**Verb conjugation class** (modern):

| Code | Class |
|---|---|
| 五 | 五段 | 4 682 |
| 下一 | 下一段 | 2 301 |
| 上一 | 上一段 | 263 |

**Particles** (助詞): 接助 postpositional/conjunctive (187), 副助 adverbial (155),
終助 sentence-final (150), 格助 case (92).

**Classical (文語) classes**, when a 文語 form is given: 四 (四段), 上二 / 下二 (二段),
カ・サ・ラ (変格), ク・シク (形容詞), ナリ・タリ (形容動詞), マス. These appear on the
old-Japanese sub-entries.

---

## 3. 形容動詞 / タルト conjugation hint (割書)

A small inset after the POS telling you which endings the adjectival headword takes:

| Code | Meaning | Example | Count |
|---|---|---|---|
| **ダナ** | 形容動詞, takes だ (predicate) / な (attributive) | 静か → 静かだ／静かな | 3 736 |
| **トタル** | タルト型: takes と (adverbial) / たる (attributive) | 堂々 → 堂々と／堂々たる | 451 |

That is exactly the `ダナ` in `名・自サ・ダナ`.

---

## 4. Register / usage label (使用域)

In `〔 〕`, marks the stylistic level. Combinations are joined with `／`.

| Label | Register | Count |
|---|---|---|
| 文 | literary / written (文章語) | 14 355 |
| 俗 | slang / colloquial (俗語) | 3 640 |
| 古風 | old-fashioned | 1 635 |
| 話 | conversational (話し言葉) | 780 |
| 雅 | elegant / poetic (雅語) | 679 |
| 方 | dialect (方言) — often qualified, e.g. 関西方言 | 94 |
| 児 | children's / baby talk | 93 |
| 男 / 女 | male / female speech | 71 / 70 |
| 学 |学生語 (student slang) | 56 |

e.g. `〔文〕` = a literary word; `〔古風／俗〕` = old-fashioned slang.

---

## 5. Field label (専門) — specialist domain

In `〔 〕` before the definition, marks a technical field. Most common in your data:

医 medicine · 理 physics/science · 法 law · 経 economics · 仏 Buddhism ·
生 biology · 音 music · 数 math · 言 linguistics · 天 astronomy · 歴 history ·
農 agriculture · 軍 military · 宗 religion · 情 IT/information ·
植物名 / 動物名 / 料理名 / 地名 (plant / animal / dish / place names) ·
野球 baseball · すもう sumo · 将棋 shogi · 碁 go · ゴルフ golf · 放送 broadcasting …

---

## 6. Brackets you'll see

| Bracket | Use |
|---|---|
| `｟ ｠` | wraps the **part-of-speech** group |
| `〔 〕` | wraps **register**, **field**, and editorial **notes** (注記), e.g. 〔後ろに否定が来る〕 |
| `【 】` | wraps the **kanji headword / 表記** |
| `「 」` | example phrase / quotation |

---

### Quick worked examples

- **`名・自サ・ダナ`** → noun + intransitive する-verb + na-adjective. (安心, 心配, 満足)
- **`×{kanji} 名`** → noun whose kanji is off the 常用 list (write it in kana).
- **`〔文〕…`** → literary-register word.
- **`〔法〕…`** → legal-domain term.
- **`→ {word}`** → go look at that other entry.

> Source: tokens and counts extracted directly from `dicts/sankoku8/term_bank_*.json`
> via the structured-content `data-name` fields (見出/品詞/割書/使用域/専門/表外字マーク).
> Meanings follow Sankoku's published 凡例 conventions.
