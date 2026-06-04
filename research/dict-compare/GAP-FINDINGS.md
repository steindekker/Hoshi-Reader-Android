# The 195 "no Japanese definition" cards — what they really are

**Date:** 2026-06-04. Dicts checked: 三省堂国語辞典 8 / 広辞苑 7 / 実用日本語表現辞典 (J-J)
+ JMdict (J-E). Script: `gap.py` (rerunnable). All 195 are in deck **言葉** and all
already **have an existing Meaning** — "missing" = won't get a fresh dictionary lookup,
not "blank card."

## Breakdown (N = 195)

| Category | n | Verdict |
|---|---:|---|
| **comma-multi** — Term is 2+ words joined by `,` (`悲劇,最中`) | 86 | **malformed; needs a decision** (split / keep-first / drop) |
| **jmdict-reading-only** — transparent compound, J-J doesn't list it | 88 | **keep** — common words, JMdict (English) is their base |
| **term-markup** — `[surface\|reading]` / `(reading)` / `<br>` baked into Term | 8 | **free fix** — strip markup → all match |
| **jmdict-written** — clean Term, only in JMdict (`１対１`, `今日中に`, `ほな`) | 6 | **keep** — J-E base |
| **no-dict-anywhere** — nothing in any of the four dicts | 7 | **drop / leave as-is** |

So of the 195: **~100 are recoverable by Term-field cleaning** (86 comma + 8 markup +
6 already-JMdict-clean), **88 are fine as English-base** (transparent compounds the
monolingual dicts deliberately omit — 駐車場, 所有者, 温暖化, 順番に…), and only **7 are
genuine drops.**

## Where they came from (origin inferred from Meaning markup)

| Source | n |
|---|---:|
| MyNote (HTML/JMdict gloss) | 75 |
| Kinomoto / yomitan | 60 |
| Kanji Study Word Model | 58 |
| jidoujisho Yuuna | 2 |

The dirty Terms are overwhelmingly a **MyNote + Kanji Study import artifact**: the
comma-joined multi-word Terms and the `[surface|reading]` markup both originate there.

## The 7 true-drop cards (nothing in any dictionary)

| Term | Reading | why nothing matches | note |
|---|---|---|---|
| 正気の沙汰ではない | しょうきのさたではない | full idiom phrase | has EN gloss already |
| 大挙して | たいきょして | conjugated (`大挙`+して) | `大挙` alone matches → deinflect |
| はっとする | はっとする | expression; 実用 lists it as `ハッとする` | reading variant |
| 液晶モニター | えきしょうモニター | 液晶+モニター, too compositional | EN "LCD" only |
| とばかりに | とばかりに | N1 grammar point | belongs in grammar, not vocab |
| ~びる | ~びる | suffix (`～びる`) | grammar/suffix |
| かちこみ | (none) | slang ("殴り込み") | no reading even; 日本語俗語辞書 only |

Of these, `大挙して` and `はっとする` are recoverable by deinflection/reading-variant;
the rest are idiom/grammar/slang — fair to drop or leave with their current Meaning.

## Recommendation

1. **Add a Term-cleaning pre-pass** to the enrichment feature: strip `<tags>`,
   `[surface|reading]→surface`, trailing `(reading)`. Free recovery of ~8 + helps matching
   generally. (98.9% of all Terms are already clean.)
2. **Comma-multi (86):** **DECIDED — flag for manual review.** Tag `ankialign::multiword`,
   skip enrichment, leave their current Meaning intact so they can be triaged in the browser.
3. **Keep the 94 J-E-only cards** — they're common compounds; English base is correct,
   matching the agreed "English becomes base where no J-J exists" rule.
4. **Drop pile is tiny (~7)** and mostly grammar/idiom/slang, not arcane vocabulary.

**Bottom line:** there is no real coverage hole. The "gap" is ~100 dirty Terms (fixable)
+ ~94 legit English-only commons + ~7 droppable grammar/slang. After a Term-cleaning
pass, effective coverage is **~99% get some definition, ~94%+ get Japanese.**
