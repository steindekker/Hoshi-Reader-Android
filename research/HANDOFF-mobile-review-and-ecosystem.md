# Handoff: Android-first review constraint + 2026 ecosystem context

Date: 2026-06-04. Source: research session on modern Anki tooling (Discord TheMoeWay/KanjiEater + web). Written for the AnkiAlign agent. Everything here is context for **goal #4 (template/styling redesign)** and confirms the unify target.

## The constraint that drove this

User mines with **jidoujisho** and **reviews almost entirely on AnkiDroid (Android)**. The redesigned card template must be excellent on AnkiDroid's WebView, not just desktop. This *validates* the existing decision to unify onto **jidoujisho Kinomoto** — it's jidoujisho's own mobile-first note type, auto-installed via AnkiDroid's API, purpose-built for exactly this pipeline.

## Platform facts (2025-2026)

AnkiDroid finished migrating from its old hand-ported backend to the **upstream Anki Rust backend** — same core as desktop. Relevant releases:

- 2.21.0 (2025-07-08) — Anki 25.02
- 2.22.2 (2025-07-25) — Anki 25.07, FSRS 6
- 2.23.0 (2025-12-09) — redesigned study screen, expanded JS API (still "technology preview")
- 2.24.0 (2026-05-02) — bulk change note types, desktop search syntax

Consequence: **CSS + standard JS parity with desktop is now good.** The remaining issues are WebView-level, not backend-level. Don't assume the old "AnkiDroid renders everything weird" reputation.

## WebView pitfalls + fixes — the actionable checklist for template work

Bake these into the goal #4 redesign:

1. **No heavy bundled custom fonts.** AnkiDroid's Chrome WebView loads `@font-face` fonts with ~1s delay (near-instant on desktop). Biggest single cause of laggy card loads. Keep fonts minimal / system fonts.
2. **Don't drive audio from JS.** HTML5 `<audio>` autoplay and JS `.play()`/`.click()` won't fire without a real user gesture. Use `[sound:...]` fields + AnkiDroid's native deck setting "Automatically play audio". (2025 quirk: "Auto Advance" misbehaves when "Don't play audio automatically" + "Wait for audio" are combined — AnkiDroid #17937.)
3. **Prefer tap-to-reveal over `:hover`.** Hover is awkward on touch. Anything hover-keyed (popup furigana, hover pitch/frequency) needs a tap fallback. Use `:active`/tap handlers.
4. **Don't rely on cross-side JS state.** Globals set on the front are gone on the back (AnkiDroid #14326); behavior diverges from desktop (#13183).
5. **Dark mode via CSS, not JS.** Use the `.nightMode` / `.night_mode` class, not JS theme switching.
6. **Keep per-card media lean** — large audio/images compound WebView slowness.
7. The **embedded JRP managed-section JS** (already in the Kinomoto template; renders pitch) must remain AnkiDroid-compatible. It currently works on mobile — don't break it when redesigning *around* it.

## Note-type landscape (if the target is ever reconsidered)

| Note type | Android verdict |
|---|---|
| **jidoujisho Kinomoto** (current target) | Mobile-first, auto-installed by jidoujisho, zero-friction. Correct choice. |
| **Lapis** (donkuri/lapis, v1.7.0 2026-01-20) | Built explicitly to kill JPMN's mobile lag. Tap-reveal via `IsClickCard`, mobile-specific CSS vars (`--mobile-sentence-position`, mobile font sizes). The polished alternative. |
| **crop-theft** (Kuuuube/crop-theft) | ~HTML+CSS only, negligible JS → sidesteps every pitfall above. Explicitly jidoujisho-compatible. Safest minimalist. |
| **JPMN** (jp-mining-note) | **Avoid on Android** — officially "no mobile support", JS-heavy, font-slow, fragile audio/pitch/furigana JS. Desktop/iOS only. |

jidoujisho ships its own types (older **Yuuna**, newer **Kinomoto**) and won't modify them after first creation, so styling tweaks persist. Its export profiles map "Creator Fields" (term, reading, sentence, image, term/sentence audio, furigana, pitch) onto any note type's fields — so Lapis/crop-theft are reachable map targets too. Two add modes: "Card Creator" (edit first) and "Instant Export" (one-tap).

## Ecosystem tools that intersect AnkiAlign goals

- **AJT Japanese** (`ankiweb 1344485230`, Ajatt-Tools) — current standard for auto furigana + pitch + audio. Worth comparing its expected field schema (SentKanji/VocabKanji etc.) against AnkiAlign's unification target; it's the de-facto field convention now alongside Lapis. Note its pitch SVG styling differs from Kinomoto's (relevant to the MyNote.Pitch vs Kinomoto.Pitch-Accent SVG mismatch already logged).
- **Local Audio Server** (`yomidevs/local-audio-yomichan`) — offline Forvo + NHK 2016 + 新明解8 audio DB (250k+ entries). Relevant to **goal #3 (Forvo word audio)**: a local DB may beat live Forvo scraping for filling the ~3-25% audio gaps. Worth a look before committing to live Forvo.
- **Audio Tools / Anki Audio Editor** (`ankiweb 1197817101`) — pitch-graph + hum-the-pitch + record-and-compare. Not for AnkiAlign itself, but it's what the user may use to *practice* on the enriched cards; confirms pitch-on-card is a valued output.
- **AnkiMorphs** (mortii, needs Anki 25.07.3+) — current MorphMan successor for i+1 ordering. Out of scope for AnkiAlign but part of the user's review stack.
- General 2026 note: FSRS, Image Occlusion, load balancing, Easy Days are now **built into Anki core** — no add-ons needed.

## Sources

- AnkiDroid changelog: https://ankidroid.org/changelog.html
- AnkiDroid issues: #13455, #10346, #14326, #13183, #17937 (github.com/ankidroid/Anki-Android)
- Lapis: https://github.com/donkuri/lapis · crop-theft: https://github.com/Kuuuube/crop-theft
- JPMN mobile FAQ: https://aquafina-water-bottle.github.io/jp-mining-note/faq/
- jidoujisho flashcard wiki: https://github.com/arianneorpilla/jidoujisho/wiki/4.-Flashcard-Creation
- donkuri mining guide: https://donkuri.github.io/learn-japanese/mining/ · TheMoeWay: https://learnjapanese.moe/mining/
- AJT Japanese: https://github.com/Ajatt-Tools/Japanese · Local Audio: https://github.com/yomidevs/local-audio-yomichan
