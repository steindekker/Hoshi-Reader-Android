# Example-sentence section — dictionary research

**Date:** 2026-06-04. Goal: an expandable "example sentences" block on Kinomoto cards.
Sources: TheMoeWay & KanjiEater Discord, plus direct inspection of the two dicts the user
downloaded into `~/Downloads`.

## The two dicts the user downloaded (last two files in ~/Downloads)

| Dict | Direction | Headword | Examples | Verdict |
|------|-----------|----------|----------|---------|
| **NEW斎藤和英大辞典** (Saito) | J→E | **Japanese** | huge, J line + E translation | **Primary source** |
| **研究社 新英和大辞典 第6版** (Kenkyūsha) | E→J | **English** | rich, native `<details>` collapsibles | Wrong index direction — supplement only |

### NEW斎藤和英 (Saito) — the right tool
- Headword is Japanese (`愛`, reading present), so it indexes directly onto our cards.
- Structured content: `<ul style=none>` of paired `<li>`: the **JP sentence** (`listStyleType:'◆'`)
  then its **EN translation** (`fontSize:70%`). Trivial to parse into (jp, en) pairs.
- The dict's own `index.json` description ships a CSS tip to cap shown examples:
  `ul.gloss-sc-ul>li:nth-child(n+5){display:none}` — i.e. it *expects* an expand/collapse UX.
- Index size: 45,235 expr / 38,159 readings; 28,211 entries have ≥1 JP example
  (median 2, mean 5.6, max 333).

**Coverage over the 8,238 in-scope Kinomoto notes:**
- Written-form match: **64.7%**; +reading recovery: **79.6%**.
- Notes that get ≥1 example sentence: **54.1%** (some Saito entries have 0 JP examples).
- Among covered notes: 1–2 ex ×1402, 3–5 ×1369, 6–10 ×945, 11+ ×741, 0 ×877.

### 研究社 新英和 (Kenkyūsha) — wrong direction for vocab cards
- E→J: headwords are English (`eponymy`); 260k entries, 24 term banks.
- Ships two variants — **with** folding elements (`折り畳み要素`) and without — plus `optional.css`
  using `<details>/<summary>`. Good native collapsible model, but to attach an example to a
  *Japanese* word we'd have to reverse-index its JP glosses/sentences (noisy). Skip for v1.

## Discord ecosystem context (TheMoeWay / KanjiEater)
- Two camps: **dictionary-embedded** examples (Jitendex, Saito, 研究社) vs **dedicated sentence
  banks**. keksmonster: *"Why use dictionary examples? There are sentence banks for that."*
  DJ_Ddawg pro-dict: *"very good, often short, definitive usage, sometimes pulled from novels."*
- Known gripe: many kokugo example sentences are **not modern Japanese** → archaic, less useful
  for learners. (Saito is old too — Meiji-era flavour — but its EN translations make it usable.)
- The named **sentence banks** people mine from:
  - **Massif** (massif.la) — #1 pick. Kuuube: ">10,000 matches"; users bulk-add ~10/card.
  - **CC100** — owned already at `~/projects/perapera/dicts/cc100.zip`. Coverage tail "drops to
    12k freq" vs Massif (Kuuube). Sudachi-parsed web corpus.
  - **BCCWJ** — balanced corpus, ~CC100 tier.
  - **Immersion Kit** (immersionkit.com) — anime/drama sentences **with audio + screenshot**,
    consumed via API/userscript, not a static Yomitan term dict.

## Recommendation for the expandable feature
1. **v1 = Saito** as the example source. Parse `term_bank_*` → (jp, en) pairs keyed by
   term+reading; store in SQLite (same pattern as pitch/JRP pipelines). Fill cards from there.
2. UX: show first 1–2 pairs, rest behind a `<details>` toggle. Saito's nth-child CSS confirms
   the pattern. Cap at e.g. 5–8 to avoid the 333-example monsters.
3. **Coverage gap is the catch:** only ~54% of cards get a Saito example. To raise that and add
   *modern* Japanese, layer in a sentence bank — **CC100 (owned) and/or Massif** — for the
   long tail / notes Saito misses. This matches the Discord meta (banks > dict examples for
   breadth).
4. Drop Kenkyūsha for vocab cards (E→J indexing); revisit only if we ever want E-headword study.

**Open questions:** (a) Show EN translation under each JP sentence, or JP-only? Saito gives both
free. (b) Acquire Massif to close the ~46% gap, or accept Saito+CC100? (c) Furigana on the JP
sentence lines (Saito sentences are bare kanji).

---

## Multi-source acquisition (2026-06-05)

Tried to "pull Massif + CC100 + Immersion Kit." Reality of each:
- **CC100** — the Yomitan "CC100" the user owns (`perapera/dicts/cc100.zip`) is a **frequency**
  dict (`term_meta_bank`, freq ranks), **not** sentences. Real CC100 sentences = the 70GB raw
  CommonCrawl corpus; no per-word API. Not practical to pull for a fill pipeline; **dropped**.
- **Massif** (massif.la) — no Yomitan sentence dict either; it's a **search site over published
  web-novels**. Scrapeable per word: `GET massif.la/ja/search?q=<term>` → `li.text-japanese`
  (sentence, matched term in `<em>`) + `.result-meta .source_link` (novel title + chapter + date,
  links to ncode.syosetu.com). ~100 hits/word. Surfaces inflected/kana forms (篩→ふるい,
  釣合い→釣り合い, 適する→適し). **Modern Japanese, good source attribution.**
  **No dump available (Discord-confirmed, 2026-06-05):** Kuuube — *"they don't release their data,
  what happens if the site just disappears one day"*; consensus that if it dies *"everything is just
  lost"* (nobody has a public archive). Massif's per-word ranking is a **server-side ML model**
  (Zakamutt), so even a raw scrape wouldn't reproduce its usefulness ordering. Bulk per-card
  scraping **gets you blacklisted** (morg was). → Massif is polite-rate-scrape-only; not a bulk
  backfill source. shoui's suggestion: *"someone needs to make a massif from tmw collection books"*
  (i.e. build our own bank from a narou/Aozora/TMW corpus). Data-releasing alternatives worth a look:
  **Jiten** (has an API), **Nadeshiko** (anime bank + backfill add-on, AnkiWeb 2105191380),
  Immersion Kit (already wired).
- **Immersion Kit** — old `api.immersionkit.com` is dead (PythonAnywhere "Coming Soon", bad cert).
  **Current API: `GET apiv2.immersionkit.com/search?q=<term>`** → `examples[]` with `sentence`,
  `sentence_with_furigana`, `translation` (EN), `title` (show), `image`, `sound`, `id`
  (`anime_<show>_<n>` — category prefix). Anime/drama, has EN + furigana. **Media caveat:** the
  Linode bucket (`us-southeast-1.linodeobjects.com/immersionkit/media/...`) returns 403/AccessDenied
  to hotlinks, and the API now sends `media:null`, so screenshot/audio aren't fetchable out-of-band
  (likely needs the official client / signed paths). Sentence+translation are fine.

### Mockup — `research/example-sentences/mockup.html`
Standalone, interactive. 10 real Kinomoto cards (freq spread: 愛嬌, 塊, 滅亡, 手触り, 統計,
がましい, 篩, 復活, 適する, 釣合い), each enriched live from all three sources (data in
`data.json`, rebuild with `build_mockup.py`). Kinomoto palette, light/dark. Toolbar toggles let
you A/B every design decision: source on/off, EN translation, term highlight, source attribution,
examples-per-source (1/2/3/all), 2-col vs stacked, compact density, expanded vs collapsed.
IK audio/screenshot shown as labelled placeholders (media not hotlinkable). Open the file in a
browser to evaluate.

**What each source brings (visible in the mockup):**
- **斎藤和英** — short, curated JP **+ EN gloss**; classic but Meiji-era flavour; ~54% card coverage.
- **Massif** — natural modern web-novel sentences, JP-only, **with book/chapter source**; broad coverage.
- **Immersion Kit** — anime/drama JP **+ EN**, would add audio + screenshot if media were reachable.
