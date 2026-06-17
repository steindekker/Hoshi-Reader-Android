# Mine: word → card via Telegram

**Status:** design approved, pre-implementation
**Date:** 2026-06-17

## Problem

The Telegram bot can only *trigger the batch pipeline* (tap Run → pick fields →
Apply). Every enrichment pass operates on existing notes, filling missing fields.
There is no way to act on a single word, and no way to *create* a card.

During immersion (asbplayer/Manatan, see immersion setup) the user hits an unknown
word and wants to add it to the deck from their phone — enriched by the same
machinery that powers the nightly run — without opening a desktop app.

This feature adds a single-word **mining** flow: send a word to the bot, get a
preview (dictionary / pitch / audio), pick a context sentence, and the bot writes
a fully-enriched `jidoujisho Kinomoto` card and syncs it to AnkiWeb.

Image attachment is explicitly **Phase 2** (separate brainstorm + spec); a no-op
hook is left in the flow for it.

## Scope

In:
- Free-text word → card creation through a stateful Telegram conversation.
- Disambiguation when a word maps to multiple dict entries.
- Reuse of existing per-`(Term,Reading)` enrichment logic via a shared
  single-note path (preview == committed value).
- Massif as the `Sentence` source, with local `examples.sqlite` fallback.
- A web-image pick for the `Image` field, reusing the merged `ankienrich/images.py`
  (headless-Chromium Bing search). The module only *searches*; this feature adds the
  Telegram picker, the download of the chosen image, and writing the `Image` field.
- Immediate sync per add, under the existing `runlock`.

Out:
- Editing/re-enriching an existing card from the bot (dedupe is its own command).
- Any change to the batch pipeline's behaviour.
- Building/altering `images.py` itself (merged from `main`); we only consume its API.

## Decisions (resolved during brainstorming)

| Question | Decision |
|---|---|
| Image step | **In scope.** After the sentence step: search via `images.search_images`, show thumbnail candidates in Telegram, user taps one or skips; download the chosen `full_url` and write the `Image` field. Search/download failure → skip the image (never dead-ends). |
| Ambiguous word | Show candidate entries (reading + 1-line gloss), user taps one. |
| Filling non-interactive fields | Shared single-note `enrich_one.build_fields` used for **both** preview and commit. |
| Sentence source | Massif top-N; fall back to local `examples.sqlite` on failure/empty. |
| Target deck | The deck existing Kinomoto cards live in (modal deck; overridable constant). Tagged `ankienrich::mined`. |
| Sync timing | Immediate per add: pull → addNote → push, under `runlock`. |
| 0-hit words | **Refuse.** No "add anyway" path. |
| HTTP client | Promote `requests` to a direct dep; use for Massif; **refactor `notify.py` off `urllib.request`** onto it for one HTTP idiom. |

## Architecture

`bot.py` stays thin (Telegram wiring only). New units:

- **`ankienrich/enrich_one.py`** — `build_fields(col, term, reading) -> dict[field, value]`.
  Composes each pass's per-`(Term,Reading)` lookup/render into the set of fields a
  new card needs (`Furigana`, `Pitch Accent`, `Term Audio`, `Meaning` +
  `Definition Source`/`Expanded Meaning`/`Original Meaning`, `Examples`). Pure w.r.t.
  Telegram; the single source of truth shared by preview and commit. Sentence-derived
  fields (`SentenceFurigana`, `Cloze Before/Inside/After`) are produced by a separate
  `fields_for_sentence(term, reading, sentence)` since the sentence arrives later in
  the flow.

- **`ankienrich/massif.py`** — `top_sentences(term, n, timeout) -> list[str]`.
  Thin massif.la scraper over `requests` with a short timeout and a UA header.
  Returns `[]` (never raises to the caller) on network error / no hits so callers
  fall back. One request per word; no caching in v1.

- **`ankienrich/mine.py`** — orchestration. The conversation state machine expressed
  as **pure functions** (mirroring today's `argv_for`/`toggled`/`keyboard_for` in
  `bot.py`), plus `create_card(col, fields, deck, tags, image)` which addNotes into the
  target deck, and the image helpers `image_candidates(term)` (wraps
  `images.search_images`, capped, never raises) and `download_image(url)` (GET via
  `requests`, bytes or None). No telebot imports → unit-testable.

- **`ankienrich/images.py`** — *merged from `main`, consumed as-is.*
  `search_images(term, limit) -> list[ImageCandidate]` (headless-Chromium Bing).
  Returns `[]` on timeout/no-results. `mine` wraps it so a missing browser or import
  error also degrades to `[]`.

- **`bot.py`** — gains a catch-all text handler (any non-button/non-command message =
  a word to mine) and callback handlers for the candidate / sentence / **image** /
  confirm keyboards. Holds per-chat conversation state in memory (keyed by chat id).

### Refactor of existing passes

Some passes entangle the per-note lookup with deck iteration. Expose the inner
per-`(Term,Reading)` function cleanly so `enrich_one` can call it:

- `pitch.accents_for(idx, term, reading)` — already exposed; reuse + `pitch_svg`.
- `audio.lookup_dict(conn, term, reading)` — already exposed; reuse (returns clip bytes).
- `defs` — extract the per-term definition lookup+render from `defs/enrich.py`'s
  note loop.
- `examples` — extract the per-`(term,reading)` example selection+render from
  `examples/enrich.py`'s note loop.
- `cloze` — extract the sentence-splitting (`Cloze Before/Inside/After`) from
  `cloze.py`'s note loop.
- Furigana generation (`Furigana`, `SentenceFurigana`) — reuse the existing
  generator used by the batch passes.

Net effect: these passes gain independently-testable per-note functions; the deck
loops call the same functions. No behavioural change to the batch pipeline.

`notify.py` is migrated from `urllib.request`/`urllib.error` to `requests`
(behaviour-preserving) so the codebase has a single HTTP client.

## Conversation flow

Per-chat state, in-memory (a bot restart drops an in-progress mine — same
limitation as today's run menu; acceptable).

```
(idle) ── user sends "髪" ──▶ LOOKUP
LOOKUP: find dict entries for the surface/reading
   ├ 0 hits   ▶ reply "not found" → end  (refuse; no add-anyway)
   ├ 1 hit    ▶ PREVIEW
   └ >1 hits  ▶ CANDIDATES  (numbered inline buttons: reading + 1-line gloss)
CANDIDATES ── tap ──▶ PREVIEW
PREVIEW:
   • dup-check the deck (same Term, same Reading).
       └ already present ▶ "already have this card" + Cancel  (no duplicate)
   • else build_fields(term, reading); reply with Reading, pitch (accent #/text),
     first gloss, and the audio clip as a Telegram voice message.
     Buttons: [Pick sentence] [Skip sentence] [Cancel]
[Pick sentence] ▶ SENTENCE
[Skip sentence] ▶ CONFIRM   (no Sentence/SentenceFurigana/cloze)
SENTENCE: massif.top_sentences(term, MINE_SENTENCE_N) → fallback examples.sqlite.
   Show ≤5 numbered + [Skip]. tap ▶ chosen Sentence → fields_for_sentence(...) ▶ IMAGE
[Skip sentence] also ▶ IMAGE
IMAGE: images.search_images(term, MINE_IMAGE_N). 
   ├ no candidates (empty / browser unavailable) ▶ note + CONFIRM (no image)
   └ else show thumbnail grid + numbered buttons + [Skip]
        ├ tap n ▶ download full_url (requests) → image bytes in session ▶ CONFIRM
        │         (download fails ▶ "couldn't fetch image" ▶ CONFIRM, no image)
        └ [Skip] ▶ CONFIRM (no image)
CONFIRM: final card summary. Buttons: [✅ Apply] [Cancel]
[✅ Apply] ▶ create_card under runlock: pull → addNote(deck, tags, image) → push
          ▶ "✅ added & synced"   (on sync error: report, card stays local)
```

## The card written

A `jidoujisho Kinomoto` note:

- Always: `Term`, `Reading`, `Furigana`, `Pitch Accent`, `Term Audio`, `Meaning`
  (+ `Definition Source` / `Expanded Meaning` / `Original Meaning` as the defs pass
  yields), `Examples` (local 例文 block).
- If a sentence was picked: `Sentence`, `SentenceFurigana`,
  `Cloze Before/Inside/After`.
- If an image was picked: `Image` = `<img src="FNAME">`, where FNAME is the chosen
  image's bytes written into `col.media` under a deterministic name.
- Target deck: modal deck of existing `NOTETYPE` notes (overridable via a config
  constant). Tag: `ankienrich::mined`.

## Sync & concurrency

`create_card` holds the existing `runlock` for pull → addNote → push, so it never
races the 04:00 nightly job, a manual `/run`, or a desktop edit. Pull-first means
remote/desktop changes are seen before the write. Volume is low (a few mined
words/day) so per-add sync cost is negligible.

## Config additions

```python
MINED_TAG = "ankienrich::mined"
MINE_SENTENCE_N = 5
MASSIF_TIMEOUT = 5  # seconds
IMAGE = "Image"            # Kinomoto field
MINE_IMAGE_N = 6           # thumbnails to offer in the picker
IMAGE_DL_TIMEOUT = 8       # seconds to download the chosen full_url
# target deck: derive as modal deck of NOTETYPE notes; optional override constant.
```

`requests>=2` added to `[project.dependencies]` (already transitive via
`pyTelegramBotAPI`). The `images` group (`playwright`) and the headless-Chromium
browser are required at runtime for the image step; without them the step degrades
to "no candidates → skip". **Deploy:** the Hetzner bot Docker image must add
`uv run playwright install --with-deps chromium` (~400 MB) — see
`docs/image-search-handoff.md`. Flagged as an explicit deploy decision, not done here.

## Error handling

- Massif failure / empty → silent fallback to local `examples.sqlite` sentences.
- Image search failure / empty / Playwright-or-browser missing → skip the image
  step (card created without an `Image`). Image *download* failure → same.
- Sync push failure → report the error to the chat; the note stays in the local
  collection and the next push (manual or nightly) retries.
- Dict-miss on the word → refuse (0-hit path); nothing to add.
- Every interactive state has a Cancel; an expired/unknown callback replies
  "session expired — send the word again" (mirrors today's run-menu behaviour).

## Testing

- `enrich_one.build_fields` / `fields_for_sentence` — per-field unit tests on
  fixtures (one assertion theme per field).
- `massif.top_sentences` — parser tested against a saved HTML fixture; **no live
  network in CI**. A separate test asserts empty-list-on-error.
- `mine` — state-machine transition functions (pure) tested directly; `create_card`
  tested against a temp collection asserting field assembly (incl. `Image` when an
  image is supplied + the media write), the dedupe guard, target deck, and
  `ankienrich::mined` tag.
- `mine.image_candidates` / `download_image` — `search_images` and `requests.get`
  monkeypatched (no live browser/network in CI); assert cap, empty-on-failure,
  bytes-on-success, None-on-download-failure.
- `notify` — existing tests must still pass after the `requests` migration (add a
  `requests`-mocked send test if not already covered).

## Out of scope / follow-ups

- Editing/re-enriching an existing card from the bot.
- Massif response caching (only if rate-limiting becomes an issue).
- Warm-browser reuse for `search_images` (handoff doc notes the option) if the
  per-call Chromium launch latency becomes annoying.
- Actually adding Chromium to the Hetzner Docker image + pushing `main`.
