# Image queue: backfill images on externally-mined cards

**Status:** design approved, pre-implementation
**Date:** 2026-06-17

## Problem

The user is moving card mining off jidoujisho onto a reader that renders 三省堂
(and the rest of the Yomitan dictionary stack) properly — e.g. Hoshi Reader, or
browser + Yomitan + AnkiConnect Android. Those tools produce good cards but have
no built-in **image search**: cards land in the collection with an empty `Image`
field.

The bot's single-word *mining* flow already solves image-picking for cards it
creates itself (search Bing via `ankienrich/images.py`, pick a thumbnail in
Telegram, download, write `Image`). Cards mined **elsewhere** never pass through
that flow, so they stay imageless.

This feature adds an on-demand **image queue**: tap a button in the Telegram bot
and walk newly-added imageless cards one at a time, reusing the exact image picker
the mining flow already has. Picking writes the `Image` field; skipping excludes
the card permanently.

Image-picking is inherently interactive, so it is **not** a nightly pipeline pass.
It lives in the bot, separate from the unattended `--apply` run. (The "missing
cloze" half of the same Hoshi gap is already covered by the existing nightly cloze
pass.)

## Scope

In:
- A persistent `🖼 画像` button in the bot that opens a stateful queue over
  newly-added imageless cards, showing each card's context + a Bing thumbnail grid.
- Pick → download the chosen image → write the existing note's `Image` field and
  clear the queue tag, under `runlock` (pull → write → push), mirroring `mine.commit`.
- Skip → swap the queue tag for a permanent `ankienrich::no-image` tag.
- A **structural** selector that defines the queue without waiting on the nightly
  pass: imageless cards that aren't `no-image` and aren't the processed back-catalog
  (so a card added from another app shows up the same day). A pipeline change still
  **flags** first-enriched imageless cards `ankienrich::needs-image` as a durability
  backstop (keeps them queued after they gain `enriched`).
- Reuse of `images.py` / `mine.image_candidates` / `mine.download_image` /
  `images.warm`/`prefetch` as-is.

Out:
- Imaging the historical back-catalog (all already carry `ankienrich::enriched`
  from the one-time bulk cleanup, so they are never flagged `needs-image`).
- Any change to image *search* (`images.py`) — consumed as-is.
- Re-imaging a card that already has an `Image`, or editing other fields.
- Making the nightly pipeline pick images (it can't — picking needs the user).

## Decisions (resolved during brainstorming)

| Question | Decision |
|---|---|
| Where does image-picking live? | **Bot-only on-demand queue.** Not a nightly pass; picking requires the user in the loop. |
| Trigger | A dedicated persistent **`🖼 画像`** button beside `▶️ 実行`. Tap → pull → build queue → walk one card at a time. |
| Which cards are offered? | **Newly-added imageless cards**, defined structurally so a card added from another app shows up the *same day* — no nightly pass required. |
| How is "newly-added" encoded? | **Structural selector, not a tag wait.** The queue offers imageless cards that are *not* `no-image` and *not* processed back-catalog: `Image:` empty `-tag:no-image ((-tag:enriched -tag:no-definition) OR tag:needs-image)`. "Processed" = the defs pass terminated on it, **either** with a definition (`enriched`) **or** without one (`no-definition` — nohit proper nouns/slang; these old cards never get `enriched`, so they must be excluded too). A fresh external add carries neither tag → shown immediately. `needs-image` is a **durability backstop**: the defs pass flags first-enriched imageless cards (`defs/enrich.py` `first_time`), so a card you *skip today* stays queued after tonight's pass enriches it. (Caveat: a *nohit* new card has no backstop — it's same-day-only, since `flag_new` only fires on a dict hit.) |
| Skip behaviour | Skipping adds `ankienrich::no-image` (permanent exclusion), mirroring `NO_EXAMPLE_TAG`. Re-imaging later means untagging by hand. |
| Image search term | The note's `Term` field (same as mining). |
| Sync timing | Per card: `runlock` → pull → write `Image` (or swap tags) → push. Same pattern and rationale as `mine.commit`. |
| Isolation of image logic | New `ankienrich/imagequeue.py` owns all image-queue policy. `defs/enrich.py` only *reports* which nids it first-enriched; it does not know about images. |

## Architecture

`bot.py` stays thin. New + changed units:

- **`ankienrich/imagequeue.py`** (new) — all queue policy, no telebot imports:
  - `flag_new(col, nids) -> int` — for each nid, if `Image` is empty and the note
    carries neither `needs-image` nor `no-image`, add `ankienrich::needs-image`.
    Returns count flagged. Called by the pipeline with the nids the defs pass
    first-enriched this run.
  - `open_queue() -> list[Candidate]` — `runlock` → `open_working` → login → pull →
    `candidates(col)` → `col.close()`. The single OPEN pull, encapsulated here so
    `bot.py` stays thin (mirrors `mine.commit` owning its own open/pull/close). Raises
    `runlock.Busy` if a run holds the lock.
  - `candidates(col) -> list[Candidate]` — notes matching the structural selector
    `{notetype} "Image:" -tag:no-image ((-tag:enriched -tag:no-definition) OR tag:needs-image)`, ordered
    by nid (creation order). `Candidate` (frozen dataclass): `nid`, `term`, `reading`,
    `context` (a short hint: the `Sentence` field JRP-surfaced via `markup.jrp_surface`,
    falling back to plain `Meaning`). Takes an already-open `col`; used by `open_queue`
    and tests.
  - `set_image(nid, image) -> dict` — `runlock` → `open_working` → login → pull →
    re-load note by nid (skip with status if it vanished remotely) → `media.write_data`
    + `Image = <img src="FNAME">` → remove `needs-image` → `update_note` → push.
    `image` is `(bytes, ext)`. Reuses `mine`'s media-naming idiom.
  - `skip(nid) -> dict` — `runlock` → pull → remove `needs-image`, add `no-image`
    → update → push.
  - `refresh(col)` style pull is done once at queue-open (see flow); `set_image`/
    `skip` each do their own pull/push so they stay consistent with cards the reader
    syncs to AnkiWeb between picks.

- **`ankienrich/defs/enrich.py`** (changed, minimal) — `enrich_notes` already knows
  `first_time` per note. Collect the nids that were first-enriched this run and
  return them as `first_enriched_nids` in its result dict. **No image logic here** —
  it only reports the transition it already computes. Dry-run (`build_plan`)
  unchanged.

- **`ankienrich/pipeline.py`** (changed) — after the `enrich` step on `apply`, call
  `imagequeue.flag_new(col, result["first_enriched_nids"])` and surface the count in
  the step report (so `--notify` can mention "N cards flagged for images"). One new
  line of wiring; no reordering of existing steps.

- **`ankienrich/bot.py`** (changed) — gains:
  - the `🖼 画像` reply-keyboard button (added to `home_keyboard`) + a
    `message_handler` matching `m.text == IMAGE_BUTTON` that opens the queue;
  - a queue conversation, kept in a **separate** in-memory dict from the mining
    `_SESSIONS`, with its own callback prefix (`q…`) so it never collides with the
    mining `img:` flow;
  - reuse of `mine.image_candidates` / `mine.image_rows` / `mine.numbered_thumbnails`
    / `mine.download_image` and `images.prefetch` (prefetch the *next* card while the
    user decides on the current one — same trick as `_enter_preview`). The picker
    presentation is the exact `_enter_image` grid: a `numbered_thumbnails`
    media-group with the URL-thumbnail + caption fallback when Pillow is absent,
    then numbered `image_rows` buttons.

### Bot wiring notes (existing handlers the queue must not break)

`bot.py` registers **one** catch-all callback handler
(`@bot.callback_query_handler(func=lambda c: True)`) which immediately looks up the
mining `_SESSIONS` and runs generic cancel/expired handling. Two consequences the
implementer must honour:

- **The `_word` miner handler guard must exclude the new button.** Today it fires on
  any non-`/` text that isn't `RUN_BUTTON`/`HELP_BUTTON`; `IMAGE_BUTTON` must be
  added to that exclusion list, or tapping 🖼 画像 gets mined as a word.
- **`q…` callbacks must be dispatched *inside* the existing catch-all handler,
  before the mining-session lookup** (a second `callback_query_handler` would never
  fire — telebot dispatches to the first matching handler). Branch on
  `data.startswith("q")` at the top of the handler and return, mirroring how the
  `is_mining` prefixes are special-cased.

## Conversation flow

Per-chat queue state, in-memory (a bot restart drops an in-progress queue — same
limitation as the mining flow; acceptable). Distinct from `_SESSIONS`.

```
(idle) ── tap 🖼 画像 ──▶ OPEN
OPEN: imagequeue.open_queue()  (runlock → open → pull → candidates → close).
   ├ 0 candidates ▶ "画像が必要な新しいカードはありません" → end
   └ ≥1           ▶ store [(nid,term,reading,context)], index 0 ▶ CARD
CARD i/N: prefetch next term's images; show "i/N 「term」(reading) — context"
          + Bing thumbnail grid (mine.image_candidates(term)) + numbered buttons.
   ├ no candidates (empty / browser down) ▶ "画像なし — スキップ" auto-skip ▶ next
   └ else buttons: [1..n] [⏭ スキップ] [■ 終了]
[n]    ▶ download full_url (mine.download_image)
            ├ ok   ▶ imagequeue.set_image(nid, image) ▶ advance
            └ fail ▶ "画像を取得できませんでした" ▶ stay on CARD i
[⏭]    ▶ imagequeue.skip(nid)  (tags no-image) ▶ advance
[■]    ▶ clear queue session ▶ "終了しました（imaged X・skipped Y）"
advance: index+1; if past end ▶ "完了（imaged X・skipped Y）" + clear session.
```

`set_image` / `skip` each run their own `runlock`-guarded pull→write→push. If a
note disappeared remotely between OPEN and the pick, the helper returns a "gone"
status and the bot just advances with a note.

## Tag / field model

- `ankienrich::needs-image` — added by `imagequeue.flag_new` on a card's first
  enrichment when `Image` is empty. **Durability backstop**, not the primary selector:
  it keeps an already-`enriched` card in the queue (the structural selector's
  not-`enriched` clause would otherwise drop it). Removed when an image is written.
- `ankienrich::no-image` — added on skip; permanent exclusion. A card can carry at
  most one of the two at a time (`set_image`/`skip`/`flag_new` keep them mutually
  exclusive).
- `Image` field — `config.IMAGE` ("Image"), written as `<img src="FNAME">` with the
  chosen bytes stored in `col.media` under a **nid-based** name (`q_{nid}.{ext}`) so
  two cards sharing a `Term`, or a future re-image, never collide on one media file
  (unlike mining's `mined_{term}.{ext}`, where each add is a fresh unique term).

Back-catalog: a processed card carries `ankienrich::enriched` (hit) or
`ankienrich::no-definition` (nohit), so the `(-tag:enriched -tag:no-definition …)`
clause excludes it. Measured on the live collection: of the 5,155 imageless cards,
5,136 are `enriched` and the remaining 19 are `no-definition` (old nohit cards like
めった from 2021) — **all** excluded, leaving 0 false positives. Genuinely new
external adds carry neither defs-pass tag and appear immediately.

## Config additions

```python
NEEDS_IMAGE_TAG = "ankienrich::needs-image"   # queue selector; set at first enrich
NO_IMAGE_TAG    = "ankienrich::no-image"      # permanent skip
IMAGE_BUTTON    = "🖼 画像"                     # persistent reply-keyboard button
# Reuses existing: IMAGE, MINE_IMAGE_N, IMAGE_DL_TIMEOUT.
```

No new dependencies: the image step already needs the `images` group + headless
Chromium (already a deploy requirement for the mining flow). Without a browser the
queue degrades per card to "no candidates → auto-skip" (does **not** tag no-image —
a browser-down auto-skip should re-offer later, so it leaves `needs-image` in place
and just advances; only a *user* skip tags `no-image`).

## Sync & concurrency

Every mutation (`set_image`, `skip`) holds the existing `runlock` for its
pull→write→push, so the queue never races the nightly job, a manual `/run`, the
mining flow, or a desktop edit. Pull-first means cards the reader pushed to AnkiWeb
since OPEN are seen. Volume is low (a few new cards/day) so per-card sync cost is
negligible. The queue does **not** hold the lock across user think-time — only the
brief OPEN pull and each individual mutation take it.

## Error handling

- No flagged cards at OPEN → friendly "nothing to image" + end.
- Image search empty / Playwright-or-browser missing → auto-skip the card **without**
  tagging `no-image` (re-offer next time); never dead-ends.
- Image *download* failure → stay on the current card with a message (user can pick
  another or skip).
- Note vanished remotely between OPEN and pick → helper returns "gone"; advance.
- Sync push failure in `set_image`/`skip` → report to chat; the local change stands
  and the next push (manual or nightly) retries; `runlock.Busy` → "実行中" retry hint.
- Every state has `■ 終了`; an expired/unknown callback replies "セッションが切れました"
  (mirrors the mining flow).

## Testing

- `imagequeue.flag_new` — temp collection: imageless+first-enrich nid gets tagged;
  a card with an `Image`, or already `no-image`/`needs-image`, is left alone; returns
  the right count.
- `imagequeue.candidates` — temp collection with a mix of tagged/untagged/imaged
  notes; asserts only `needs-image` notes returned, ordered by nid, with term/reading/
  context populated. (`open_queue`'s sync/runlock wrapper is monkeypatched in its own
  thin test; the selection logic is covered here.)
- `imagequeue.set_image` — temp collection: writes `Image` with the media file,
  removes `needs-image`; "gone" status when the nid is absent. (`sync`/`runlock`
  monkeypatched — no live network.)
- `imagequeue.skip` — removes `needs-image`, adds `no-image`.
- `defs/enrich.enrich_notes` — existing tests still pass; add an assertion that
  `first_enriched_nids` contains exactly the nids tagged `enriched` this run and is
  empty on a re-run (idempotent).
- `pipeline.run` — `flag_new` is called with the defs step's first-enriched nids on
  apply, and not on dry-run; step report carries the flagged count.
- Bot queue state-machine helpers (pure: `q…` row builders / advance logic) tested
  directly, as the mining helpers are. `mine.image_candidates`/`download_image`
  already covered; reused unchanged.

## Out of scope / follow-ups

- Imaging the back-catalog (deliberately excluded).
- Re-imaging or un-skipping from the bot (today: untag `no-image` by hand).
- Batching all picks into a single push instead of per-card (only if per-card sync
  becomes annoying).
- A preview-time duplicate/no-image count in `--notify` beyond the flagged count.
- Note-type alignment if mining moves to Hoshi: Hoshi auto-configures the **Lapis**
  note type, but the whole pipeline (and this queue) targets `jidoujisho Kinomoto`.
  Pointing Hoshi's fields at Kinomoto is a prerequisite for any of this to see those
  cards — tracked separately, orthogonal to this design.
```
