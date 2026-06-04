# Definition (`Meaning`) cleanup — options & recommendation

**Date:** 2026-06-04. Synthesizes three parallel investigations. The user's complaint:
"so much visual clutter — `<>` tags and stuff I don't care about."

## Two distinct problems

1. **Literal clutter** — HTML tags + inline styles (MyNote/Kinomoto), Kanji-Study
   furigana markup `しり[,しる;o]`, decorative symbols (🈩►㊀◨↔), romaji `[ローマ字](…)`,
   dict-name headers.
2. **Volume** — 3–4 dictionaries concatenated per word; original Kinomoto entries reach
   1,500–1,700 chars (walls of `<br>`).

Different sources have different shapes (MyNote JMdict-HTML; Yuuna `【…】` plain text;
Kanji Study markup; Kinomoto yomitan `<br>` walls), so no single trick covers all.

## The three approaches investigated

### A. Deterministic strip (offline, rule-based) — `clean_meaning_prototype.py`
Per-source parsers reduce each format to clean minimal HTML / plain text. **Runs; verified.**
- MyNote `それ` 414→170 chars; Kinomoto `異変` 1671→601 (64%), `綿密` 1611→531 (67%);
  Kanji Study `気合` 732→346 (53%).
- Kills problem #1 cleanly. Keeps **all** content. Fully offline, deterministic, reversible.
- Limits: Yuuna 新明解 sub-entries leak; Kanji-Study leaves inter-word spaces (`精神 を 集中`);
  Kinomoto dict-header whitelist needs occasional extension.

### B. Structural reduction + CSS polish (offline restructure)
Pick a **primary dictionary**, move the rest into a `<details>` "More" (tap-to-expand,
AnkiDroid-friendly). Kills problem #2: `異変` shows ~70 chars first (96% less), extras one tap away.
- Primary-dict priority (match `【header】`/`data-dictionary`):
  旺文社国語辞典 → デジタル大辞泉 → 新明解国語辞典 → 明鏡 → KireiCake → JMdict → 研究社 → NEW斎藤.
- **CSS-only is a dead end here**: only the Kinomoto/yomitan subset has stylable
  `data-sc-content`/`data-dictionary` hooks (good for hiding attribution/examples, à la
  Lapis `--jitendex-format`). Yuuna plain text and Kanji-Study markup have no element
  hooks → must be restructured in Python. CSS is a *complement*, not the primary tool.
- Best applied **after** A (strip, then collapse).

### C. One-time LLM rewrite (online, opt-in) — `report-llm.md`
Batch-rewrite every definition into concise English. Handles all formats uniformly; best readability.
- ~8,171 notes, **~$2.40 via Haiku 3.5 Batch API**, 20–40 min. (Sonnet ~$9 if quality needs it.)
- `異変` 1671 → "unusual occurrence; abnormal event / sudden change for the worse";
  `綿密` 1611 → "meticulous, thorough, detailed (na-adj)".
- English output (sources are EN/Dutch; J-J kept only as input context for nuance).
- **Tradeoffs:** needs network/API (breaks the project's offline-first stance); lossy
  (drops examples/nuance — though faithful in samples); mild non-determinism (temp 0).
  Mitigations: standalone opt-in script (add-on stays offline); preserve raw in
  `Expanded Meaning`; forced backup; output cache + resumable.

## Tradeoff summary

| | A. Strip | B. Collapse | C. LLM |
|---|---|---|---|
| Removes `<>`/markup/symbols | ✅ | (relies on A) | ✅ |
| Fixes 4-dict wall-of-text | ❌ (keeps all) | ✅ | ✅ |
| Offline / deterministic | ✅ | ✅ | ❌ online, ~$2.40 |
| Reversible / lossless | ✅ | ✅ (extras collapsed) | ⚠ lossy (raw kept in field) |
| Readability ceiling | good | good | best |
| Effort | low | medium | medium (separate script) |

## Recommendation

**Do A + B as the core** (deterministic strip → primary-dict + `<details>` collapse).
It directly targets the user's stated complaint (clutter + wall-of-text), stays fully
offline / deterministic / reversible (matching AnkiAlign's safety ethos), and keeps every
dictionary one tap away. Wire it as a new opt-in step in the add-on (like `normalize_media`),
writing the cleaned/restructured value back to `Meaning` (raw preserved by the forced backup;
optionally also stash raw in a field).

**Offer C as an optional later layer** for whoever wants ultra-concise English glosses —
it's cheap (~$2.40) but online + lossy, so it should be an explicit, separate opt-in, not
the default.

Open decision for the user: is the complaint "too much *junk*" (→ A+B is enough) or "too
much *detail*, I want a short English gloss" (→ add C)? Pick A+B, or A+B+C.

## Artifacts
- `samples.md` — real clutter across all 4 sources.
- `clean_meaning_prototype.py` — runnable deterministic cleaner (A); `uv run python …`.
- `report-llm.md` — LLM approach (C): prompt, cost, safety, hand-done before/after.
- (Deterministic & structural agents' detailed findings are folded into this doc; their
  standalone reports weren't persisted, but the prototype is the deterministic artifact.)
