# Best Japanese dictionaries 2026 — esp. monolingual (research)

**Date:** 2026-06-04. Sources: TheMoeWay Discord (incl. **Shoui** directly), KanjiEater
Discord, TheMoeWay Monolingual Guide, MarvNC/yomitan-dictionaries (current canonical list).

## What this user uses now (from their cards)
- **J-J:** デジタル大辞泉 (2310 notes), 旺文社国語辞典 第11版 (2385), 新明解 **第7版** (192).
- **J-E:** JMdict (2475), 研究社 新和英 第5版 (2033), NEW斎藤和英 (1473), KireiCake (204).
- **Owns locally (Yomitan):** 大辞泉(daijisen-2), 新明解8(shinmeikai-8), 実用(jitsuyo), jitendex, jmdict.
- **Missing the current community favourite:** 三省堂国語辞典 (Sankoku) and 明鏡 3 (Meikyo).

## Shoui's recommendations (direct quotes, TheMoeWay)
- **2026-05-29:** "There is no 'best monolingual dictionary', what I recommend is a *stack*:
  広辞苑 第七版 / 新明解国語辞典 第八版 / 大辞林 第四版 / 三省堂国語辞典 第八版 / 実用日本語表現辞典 / PixivLight."
- **2025-12-23:** "**daijisen 2nd edition and sankoku 8th edition are the best dictionaries.**"
- **2026-02-15:** (to someone choosing) "**do daijisen+sankoku.**"
- Note: Meikyo 3 (with furigana, via CSS) now exists for Yomitan and Shoui has discussed it.

## Community consensus (TheMoeWay + KanjiEater, 2024–2026)
- **三省堂国語辞典 第八版 (Sankoku 8)** — the repeated pick for *readable* learner definitions:
  "sankoku is fairly easy to understand", "I put sankoku first, it's pretty easy to
  understand", "sankoku then jitendex". A user mining now: "currently using 三省堂国語辞典 第八版".
  axiom: "daijisen just for the sheer number of entries **or sankoku for the definition**";
  "Sankoku and oubunsha usually have **direct definitions with easy-to-understand words**."
- **明鏡国語辞典 (Meikyo, 2nd/3rd)** — "meikyo my beloved" (×multiple), "I'd put meikyo higher,
  it's good"; great for **kanji-usage nuance**; Meikyo 3 conversion (caoimhe, 2025-03) adds
  **furigana** + dark-mode CSS, better coverage than the 2nd.
- **新明解 第8版 (SMK8)** — distinctive definitions; "Sankoku and SMK complement each other
  really well"; in the "important ones: smk8, sankoku, daijirin 4".
- **大辞泉 / 大辞林 第四版** — comprehensive coverage ("the QUEEN"; "great all-rounder").
- TheMoeWay's older beginner guide ranks 旺文社11 #1, 明鏡 #2 — but the live Discord meta in
  2025-26 has clearly shifted to **Sankoku (readable) + Daijisen (coverage)** as the core pair.

## Where to get the missing ones (Yomitan, 2026)
- MarvNC/yomitan-dictionaries lists Sankoku 7 & 8, Meikyo 2 & 3, Daijirin 3/4 (MediaFire),
  SMK8, 旺文社12, 小学館例解学習12 (best *beginner* mono, has furigana+examples).
- TheMoeWay `#dictionaries` recently shared a **lighter plaintext Sankoku** (manhhao, 2026-06-02)
  and a plaintext JMdict+Daijirin+Sankoku combo.

## Recommendation for THIS user (J-J base feature)
The user wants a clean Japanese definition as the base. Best-practice 2026 = **Shoui's
"daijisen + sankoku" pair**, with Sankoku as the readable primary and Daijisen as the
comprehensive fallback:

1. **Acquire 三省堂国語辞典 第八版 (Sankoku 8)** in Yomitan format (MarvNC collection / TheMoeWay).
   It's the readable, concise, learner-oriented J-J the community standardises on — the one
   thing the user is missing.
2. **Base lookup chain: Sankoku 8 → 大辞泉 (owned) → 新明解 8 (owned).**
   - Sankoku gives the most readable definition where it has the word (smaller dict).
   - 大辞泉 (94% coverage measured) fills the gaps; 新明解 8 as final J-J fallback.
   - Combined coverage measured at **96%** even *without* Sankoku, so adding Sankoku only
     improves readability, never hurts coverage.
3. Optional: add **明鏡 3** (furigana + kanji-nuance) if the user wants per-word furigana in
   definitions; **広辞苑7 / 大辞林4** only if they want maximal coverage (diminishing returns).
4. Keep the existing **English/Dutch** (JMdict/研究社) below, de-cluttered — as already decided.

**Bottom line:** match Shoui — standardise the J-J base on **Sankoku 8 (readable) + Daijisen
(coverage)**, both as a fallback chain. Only Sankoku 8 needs to be obtained; everything else
the user already has.

---

## FINAL DECISION (2026-06-04, after coverage + gap analysis)

User acquired Sankoku 8, Daijirin 4, Koujien 7, SMK8, Jitsuyo, Daijisen (Yomitan).
After building the side-by-side comparison harness (`research/dict-compare/`) and measuring
real coverage over the 8,247 in-scope Kinomoto notes (post multi-word split), the locked set is:

- **J-J base chain:** 三省堂国語辞典 8 → 広辞苑 7 → 実用日本語表現辞典 → 大辞泉
  - 三省堂 = readable primary; 広辞苑 = fallback, **styled to resemble 三省堂**;
    実用 = expressions; 大辞泉 = deep coverage fallback (recovers ~30 compounds).
- **J-E:** JMdict — short **numbered** glosses (`1. a; b; c` / `2. …`), placed **below** the JP.
- Coverage achieved: **J-J 99.0%**, **any-def 99.8%**; only ~7 true orphans (grammar/idiom/slang).
- Dropped from consideration: 新明解 8 (adds only ~5 of 95 compound gaps — not worth it);
  大辞林 4 (offered +27 but user chose 大辞泉-only to keep the chain shorter);
  Jitendex (covers all J-E cases but JMdict chosen as the J-E source).

See `research/dict-compare/GAP-FINDINGS.md` and `coverage.py`/`check_others.py` for the data.

### Multi-word sentence cards — RESOLVED (live, 2026-06-04)
86 cards had comma-joined multi-word Terms (`悲劇,最中`). Split into per-word cards
(`split_multiword.py`), inheriting the original card's review schedule: **155 new cards,
86 originals removed**, tagged `ankialign::split-from-multiword`. Backup
`backup-2026-06-04-21.15.59.colpkg`. The gap fell 195 → 110.
