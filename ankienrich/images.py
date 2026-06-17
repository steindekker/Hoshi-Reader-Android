"""Bing-image candidate search for term cards.

The results grid is built by Bing's JavaScript, so a plain HTTP fetch returns a
stale/empty feed and misses on Japanese terms — the same reason jidoujisho drives
a real browser. We render the page in headless Chromium and read each `a.iusc`
element's `m`-attribute JSON (the same hook jidoujisho uses). Output is candidate
URLs only; downloading the chosen image is the caller's concern.

Setup (besides the `images` dependency group): `uv run playwright install chromium`.

Usage:
    from ankienrich.images import search_images
    for c in search_images("林檎", limit=12):
        c.thumb_url   # Bing CDN thumbnail — show this in the Telegram grid
        c.full_url    # original source image — store/download once chosen
"""
from __future__ import annotations

import atexit
import concurrent.futures
import json
from collections.abc import Iterable
from dataclasses import dataclass
from urllib.parse import quote

# adlt=off disables SafeSearch (single-user tool — unfiltered results wanted). The
# URL param alone is unreliable; Bing also reads it from the SRCHHPGUSR cookie set
# on the browser context (see _SAFESEARCH_COOKIE).
SEARCH_URL = "https://www.bing.com/images/search?q={q}&form=HDRSC2&adlt=off"

# SafeSearch-off cookie Bing actually honours (the URL param is advisory).
_SAFESEARCH_COOKIE = {"name": "SRCHHPGUSR", "value": "ADLT=OFF",
                      "domain": ".bing.com", "path": "/"}

# A real desktop UA is REQUIRED: headless Chromium's default `HeadlessChrome` UA
# makes Bing serve an unrelated default feed (a query for 林檎 came back as Swedish
# wedding / Portuguese-botany results). A normal UA returns the true results.
_USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
               "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

# Resource types to drop while rendering: we only need the DOM the page-JS builds,
# not the pixels (jidoujisho's `blockNetworkImage: true`). Keeps scripts/XHR alive.
# (Stylesheets are NOT blocked — measured slower + fewer grid items without them.)
_BLOCKED_RESOURCES = {"image", "media", "font"}

# Wait for at least this many grid items before reading (or until the grace cap),
# so a fast read can't catch a half-rendered grid with one item.
_RESULT_FLOOR = 12


@dataclass(frozen=True)
class ImageCandidate:
    """One Bing image result. `thumb_url` (turl) is a uniform, hotlink-safe Bing
    CDN thumbnail — good for a Telegram preview grid; `full_url` (murl) is the
    original source image."""

    thumb_url: str
    full_url: str
    title: str
    source_page: str


def parse_iusc(m_attrs: Iterable[str]) -> list[ImageCandidate]:
    """Map raw `a.iusc` `m`-attribute JSON strings to candidates."""
    out: list[ImageCandidate] = []
    seen: set[str] = set()
    for raw in m_attrs:
        try:
            d = json.loads(raw)
        except (ValueError, TypeError):
            continue
        thumb, full = d.get("turl", ""), d.get("murl", "")
        if not thumb or not full or full in seen:
            continue
        seen.add(full)
        out.append(ImageCandidate(
            thumb_url=thumb,
            full_url=full,
            title=d.get("t", ""),
            source_page=d.get("purl", ""),
        ))
    return out


def _block_media(route) -> None:
    if route.request.resource_type in _BLOCKED_RESOURCES:
        route.abort()
    else:
        route.continue_()


# --- Warm browser on a single dedicated worker thread --------------------------
#
# All Playwright work runs on ONE worker thread (a 1-slot executor): the sync API
# is thread-bound, so this keeps the browser/page on a consistent thread while the
# telebot main thread only submits jobs and reads futures. The browser + one page
# are kept warm, so launching Chromium + the driver (the ~2 s bulk of a search) is
# paid once. `prefetch` lets the bot start a search while the user is still picking
# a sentence, so the result is usually ready by the image step.
_EXECUTOR = None        # the single worker thread; created lazily
_PW = None              # } worker-thread-owned: only touched inside tasks that
_BROWSER = None         # } run on _EXECUTOR, never from the main thread.
_PREFETCH: dict[str, concurrent.futures.Future] = {}  # term -> in-flight search


def _executor() -> concurrent.futures.ThreadPoolExecutor:
    global _EXECUTOR
    if _EXECUTOR is None:
        _EXECUTOR = concurrent.futures.ThreadPoolExecutor(
            max_workers=1, thread_name_prefix="img-search")
    return _EXECUTOR


def _browser():
    """The warm browser on the worker thread; relaunched if it has died. The
    browser launch is the costly part — kept warm; each search gets a FRESH
    context+page (reusing a page races Bing's SPA → partial result grids)."""
    global _PW, _BROWSER
    if _BROWSER is not None and _BROWSER.is_connected():
        return _BROWSER
    from playwright.sync_api import sync_playwright
    if _PW is None:
        _PW = sync_playwright().start()
    _BROWSER = _PW.chromium.launch(headless=True)
    return _BROWSER


def _reset() -> None:
    """Drop the (possibly dead) browser so the next search relaunches it. Keeps the
    driver (`_PW`) alive — only the browser needs relaunching."""
    global _BROWSER
    try:
        if _BROWSER is not None:
            _BROWSER.close()
    except Exception:
        pass
    _BROWSER = None


def _search_blocking(term: str, timeout_ms: int) -> list[ImageCandidate]:
    """The actual search. Runs ONLY on the worker thread. Returns all candidates
    (callers slice). [] on no-results/timeout; self-heals the browser on a crash."""
    from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
    context = None
    try:
        context = _browser().new_context(user_agent=_USER_AGENT)
        context.add_cookies([_SAFESEARCH_COOKIE])      # SafeSearch off
        page = context.new_page()
        page.route("**/*", _block_media)
        page.goto(SEARCH_URL.format(q=quote(term)), wait_until="domcontentloaded")
        try:
            page.wait_for_selector("a.iusc", timeout=timeout_ms)
        except PlaywrightTimeoutError:
            return []
        # The grid streams in; reading at the first `a.iusc` can catch a single
        # item. Wait for a healthy batch (returns early once present); a short cap
        # so niche terms with few results don't wait the whole grace period.
        try:
            page.wait_for_function(
                "n => document.querySelectorAll('a.iusc').length >= n",
                arg=_RESULT_FLOOR, timeout=1500)
        except PlaywrightTimeoutError:
            pass
        m_attrs = page.eval_on_selector_all(
            "a.iusc", "els => els.map(e => e.getAttribute('m')).filter(Boolean)")
        return parse_iusc(m_attrs)
    except Exception:
        _reset()
        return []
    finally:
        if context is not None:
            try:
                context.close()
            except Exception:
                pass


def _warm() -> None:               # runs on the worker thread
    try:
        _browser()
    except Exception:
        pass


def warm() -> None:
    """Launch the warm Chromium ahead of the first search so its ~2 s cold start is
    paid at startup, not on the first word. Fire-and-forget; never blocks."""
    _executor().submit(_warm)


def prefetch(term: str, *, timeout_ms: int = 8000) -> None:
    """Kick off a search for `term` in the background so `search_images(term)`
    later returns (near-)instantly. No-op if one is already in flight."""
    if term in _PREFETCH:
        return
    if len(_PREFETCH) > 8:          # bound abandoned-flow leakage (single user)
        _PREFETCH.clear()
    _PREFETCH[term] = _executor().submit(_search_blocking, term, timeout_ms)


def search_images(term: str, limit: int = 12, *, timeout_ms: int = 8000) -> list[ImageCandidate]:
    """Up to `limit` Bing image candidates for `term`. Consumes a `prefetch`ed
    result if one is in flight (waiting only for the remainder), else runs now.
    Returns [] if the grid never appears. Requires the `images` dep group and
    `playwright install chromium`."""
    fut = _PREFETCH.pop(term, None)
    if fut is None:
        fut = _executor().submit(_search_blocking, term, timeout_ms)
    return fut.result()[:limit]


def shutdown() -> None:
    """Tear down the worker browser + driver. Best-effort, for clean process exit."""
    global _EXECUTOR
    ex = _EXECUTOR
    if ex is None:
        return
    try:
        ex.submit(_teardown).result(timeout=5)
    except Exception:
        pass
    ex.shutdown(wait=False)
    _EXECUTOR = None
    _PREFETCH.clear()


def _teardown() -> None:           # runs on the worker thread
    global _PW, _BROWSER
    try:
        if _BROWSER is not None:
            _BROWSER.close()
        if _PW is not None:
            _PW.stop()
    except Exception:
        pass
    _PW = _BROWSER = None


atexit.register(shutdown)
