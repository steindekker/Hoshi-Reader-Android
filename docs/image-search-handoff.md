# Image search — handoff

A self-contained module that turns a **term** into a list of **candidate images**
to choose from. Built to be dropped into the (separate) "add a card via Telegram"
flow: the term-only card add searches images, the user picks one in Telegram, and
the chosen image goes onto the new card's `Image` field. **This module does only
the search.** Downloading the chosen image and writing the card are the caller's job.

## API

```python
from ankienrich.images import search_images, ImageCandidate

cands = search_images("林檎", limit=12)   # -> list[ImageCandidate]
```

`ImageCandidate` (frozen dataclass):

| field         | source key | use                                                        |
|---------------|------------|------------------------------------------------------------|
| `thumb_url`   | `turl`     | Bing CDN thumbnail — uniform, hotlink-safe → Telegram grid  |
| `full_url`    | `murl`     | original source image — download/store once chosen          |
| `title`       | `t`        | result title (handy as a caption / debug)                   |
| `source_page` | `purl`     | page the image came from (attribution, if ever wanted)      |

`search_images(term, limit=12, *, timeout_ms=8000)` returns up to `limit`
candidates, deduped by `full_url`, order preserved. Returns `[]` if the grid never
appears (no results, or a slow/blocked load past `timeout_ms`).

`parse_iusc(m_attrs)` is the pure JSON-mapping core (no browser) — unit-tested
directly; reuse it if you scrape the page yourself.

## How it works (and why)

Bing builds the results grid in JavaScript, so a plain HTTP fetch (or Bing's
`/images/async` endpoint) returns a stale/default feed and **misses entirely on
Japanese terms**. So we render the page in **headless Chromium** (Playwright) and
read each `a.iusc` element's `m`-attribute JSON — the same hook jidoujisho's
`bing_images_search_enhancement.dart` uses. We block image/media/font resource
types while rendering (jidoujisho's `blockNetworkImage: true`): we only need the
DOM the page-JS builds, not the pixels.

### Two non-obvious gotchas (don't undo these)

1. **A real desktop User-Agent is mandatory.** Headless Chromium's default
   `HeadlessChrome` UA makes Bing serve an unrelated default feed (a query for 林檎
   came back as Portuguese-botany / Swedish-wedding results). A normal UA returns
   the true results. The UA is set on the browser context in `images.py`.
2. **The browser is not optional.** Plain HTTP, the `/images/async` endpoint, and
   the DuckDuckGo `i.js` JSON endpoint were all tried and rejected: erratic/wrong
   results, blocked (403), or broken on Japanese terms. Headless Chromium is the
   only reliable path.

## Tests

`tests/test_images.py`:

- 4 offline unit tests on `parse_iusc` (field mapping, malformed-JSON skip,
  missing-URL skip, dedupe). Run in the normal suite.
- 1 **opt-in** live integration test, gated by `ANKIENRICH_LIVE_IMAGE_TEST=1`
  (skipped by default — no network/chromium in CI). It asserts *relevance* (the
  term shows up in the results), which is what catches the UA junk-feed regression.

```bash
uv run pytest tests/test_images.py            # offline (live test skipped)
ANKIENRICH_LIVE_IMAGE_TEST=1 uv run pytest tests/test_images.py   # hits Bing
```

## Integration checklist (for the card-creation session)

- **Deps:** `images` group in `pyproject.toml` carries `playwright`. The browser
  binary is separate: `uv run playwright install chromium`.
- **Latency:** each call launches and tears down Chromium → ~1.5–3 s, blocking.
  Fine for an interactive card add. If the long-running bot needs it snappier,
  refactor `search_images` to reuse a warm browser/context — the public API and
  `parse_iusc` won't change.
- **Hetzner Docker** (`Dockerfile`, bot container) — add the browser + its system
  libs. Roughly:
  ```dockerfile
  RUN --mount=type=cache,target=/root/.cache/uv \
      uv sync --frozen \
      && uv run playwright install --with-deps chromium
  ```
  `--with-deps` pulls the apt libraries headless Chromium needs on
  `python:3.11-slim`. This adds ~300–450 MB to the image — left as an explicit
  integration decision, not baked in here.
- **Telegram UX (suggested):** send `thumb_url`s as a media group / inline-button
  grid; on pick, download `full_url`, hand it to the card writer. Out of scope for
  this module.
