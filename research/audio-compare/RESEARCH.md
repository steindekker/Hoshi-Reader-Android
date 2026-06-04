# Audio source comparison: pitch-accent accuracy for AnkiAlign backfill

**Date:** 2026-06-04
**Question:** Of the four `local-audio-yomichan` offline collections (nhk16, shinmeikai8, jpod, forvo), which is most pitch-accent-accurate, and what priority order should an automated "pick one best recording" backfill use?

## Verdict

**Best single source for pitch-accent accuracy: nhk16 (NHK 2016 accent dictionary).** It is the recognized gold standard for 標準語/Tokyo-normative pitch, recorded by professional NHK announcers from the dictionary that broadcasters are *required* to follow.

**Recommended priority chain: `nhk16 → shinmeikai8 → forvo → jpod`.**

This matches the current upstream default in `local-audio-yomichan`, which the maintainers chose explicitly "to optimize for **Japanese correctness over literal audio quality**" (README; changed in v1.6.0 from the old `jpod,nhk16,shinmeikai8,forvo`). The two accent dictionaries lead because their pitch is curated/authoritative; Forvo is placed above jpod because, while crowd-sourced and noisier, real native recordings are preferred over JapanesePod101's stylized renderings. Note this ordering optimizes for **pitch correctness**, not audio cleanliness — Forvo's raw audio is the worst of the four (the upstream collection normalizes volume and trims silence to compensate).

## Per-source assessment

### 1. nhk16 — NHK日本語発音アクセント新辞典 (2016)
- **Authority for pitch:** Highest. The NHK accent dictionary is "basically the gold standard… the most comprehensive and authoritative resource," most recently revised (first time in 18 years), and is the normative reference broadcasters/news presenters must follow.
- **Voice/consistency:** Professional NHK announcers, ~100k+ recorded words/phrases, very consistent.
- **Reading-correctness:** Keyed by (term, reading); dictionary-disambiguated, so homographs (端/橋/箸, etc.) get the right accent for the right reading.
- **Failure mode / nuance:** Coverage gaps on rare/mined vocabulary, names, slang. Also, standard Japanese permits *multiple* valid accents for some words; the dictionary lists each with the most common first, so a single recording is "an" accepted accent, not necessarily "the only" one. This is a non-issue for review correctness.

### 2. shinmeikai8 — 新明解国語辞典 第八版 accent recordings
- **Authority for pitch:** High and dictionary-curated; an established accent reference alongside NHK ("for those wanting to aspire to NHK announcer pronunciation standards"). Slightly behind NHK only because NHK is the broadcast normative standard and more recently/comprehensively accent-focused.
- **Voice/consistency:** Curated dictionary audio, consistent.
- **Reading-correctness:** Keyed by (term, reading); disambiguated.
- **Failure mode:** Smaller coverage than NHK; occasional accent-convention differences from NHK but still standard.

### 3. forvo — Forvo.com crowd-sourced
- **Authority for pitch:** Low / variable. Recordings are native but **not accent-dictionary-vetted**, and contributors may use regional dialects (e.g. Kansai 雨/飴 inversions, or 川 as kaꜜwa vs Tokyo kawaꜜ). No quality gate on pitch.
- **Voice/consistency:** Inconsistent — many speakers, variable mic/room. Upstream notes "Forvo audio is very inconsistent in raw audio quality" and applies normalization + silence trimming. The local-audio collection restricts to *select* Forvo users to raise the floor.
- **Reading-correctness:** **Weakest point — Forvo is term-only with NO reading disambiguation.** For a homograph the returned clip may use the wrong reading entirely, which also implies wrong pitch. This is the main reason it must sit *below* both dictionaries despite good coverage.
- **Coverage:** Best of the four for the long tail — names, slang, rare/mined words.

### 4. jpod — JapanesePod101 word bank (~2020)
- **Authority for pitch:** Lowest for our purpose. TheMoeWay (the community's setup guide) explicitly warns it is "not recommended… JapanesePod101 **sometimes pronounces words with the wrong pitch** and has too many missing words." Conversational/stylized delivery rather than citation-form accent.
- **Voice/consistency:** Single consistent voice, clean studio audio — its one strength (and why it was the *old* default).
- **Reading-correctness:** Keyed by (term, reading), so reading itself is fine; pitch is the liability.
- **Failure mode:** Occasional non-standard/stylized accent; coverage gaps. Audio is clean but can be confidently wrong on pitch — actively harmful for a pitch-review workflow, hence last.

## Caveats / when to override

- **Pitch is the deciding axis, not audio fidelity.** Do not promote jpod for sounding cleaner; a clean wrong-accent clip is worse than a noisy correct one for this user.
- **Homographs:** Always prefer a (term, reading)-keyed source (nhk16/shinmeikai8/jpod) when the reading is known. Treat Forvo as term-only: only trust it when the term is effectively monomorphic in reading, or accept reading-mismatch risk knowingly. If the card's reading is known and a dictionary hit exists, never fall through to Forvo for that card.
- **When Forvo's coverage is worth the risk:** proper nouns, names, slang, and rare mined vocabulary that NHK/Shinmeikai simply don't cover. Here Forvo (or jpod) beats *no audio*. For these, prefer Forvo over jpod since native > stylized, but flag/spot-check pitch since it is unverified.
- **Multiple permissible accents:** for words NHK lists with several accents, the single bundled clip is one valid form. No action needed, but don't treat divergence between an NHK clip and an accent-dictionary lookup as an error.
- **Practical:** "stop at first source with a hit" semantics + this order yields: dictionary-correct pitch for the common core, native fallback for the tail, jpod only as a last resort.

## Sources

- local-audio-yomichan README (default order + "Japanese correctness over literal audio quality", Forvo audio-quality note): https://github.com/yomidevs/local-audio-yomichan
- local-audio-yomichan CHANGELOG (v1.6.0 default order change to nhk16,shinmeikai8,forvo,jpod): https://github.com/yomidevs/local-audio-yomichan/blob/master/CHANGELOG.md
- TheMoeWay / learnjapanese.moe Yomitan setup (JPod101 "wrong pitch", recommends local audio): https://learnjapanese.moe/yomichan/
- Animecards "Ultimate Yomitan Audio Source" (source selection, human vs TTS): https://animecards.site/yomitan_audio/
- AJATT / tatsumoto "Japanese support for Anki" (dictionary audio "pretty accurate", source-ordering "stop at first hit"): https://tatsumoto-ren.github.io/blog/anki-japanese-support.html
- NHK Japanese Pronunciation Dictionary (2016 ed., 75k headwords, announcer audio, broadcast standard): https://www.monokakido.jp/en/dictionaries/nhkaccent2/
- Wikipedia, Japanese pitch accent (NHK normative status; dialect & homograph accent variance; multiple permissible accents): https://en.wikipedia.org/wiki/Japanese_pitch_accent
- Shinmeikai accent dictionary overview (curated accent reference): https://selftaughtjapanese.com/2014/08/07/shinmeikai-japanese-accent-dictionary/
