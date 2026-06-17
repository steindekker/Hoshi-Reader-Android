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
_BLOCKED_RESOURCES = {"image", "media", "font"}


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


# A warm, reused browser. Launching Chromium + the Playwright driver is the bulk
# of a search's latency (~2 s); the long-lived bot is single-threaded, so we keep
# one browser alive and open a cheap fresh context per search. Only the first
# search after start (or after a crash) pays the cold start.
_PW = None
_BROWSER = None


def _browser():
    """The shared headless-Chromium browser, launched once and relaunched if it
    has died. Must only be called from one thread (the bot runs single-threaded)."""
    global _PW, _BROWSER
    if _BROWSER is not None and _BROWSER.is_connected():
        return _BROWSER
    from playwright.sync_api import sync_playwright
    if _PW is None:
        _PW = sync_playwright().start()
    _BROWSER = _PW.chromium.launch(headless=True)
    return _BROWSER


def shutdown() -> None:
    """Tear down the warm browser + driver. Best-effort, for clean process exit."""
    global _PW, _BROWSER
    try:
        if _BROWSER is not None:
            _BROWSER.close()
        if _PW is not None:
            _PW.stop()
    except Exception:
        pass
    finally:
        _BROWSER = _PW = None


atexit.register(shutdown)


def search_images(term: str, limit: int = 12, *, timeout_ms: int = 8000) -> list[ImageCandidate]:
    """Search Bing Images for `term`, returning up to `limit` candidates.

    Renders the JS-built results grid in a warm headless Chromium (see `_browser`)
    and reads the `a.iusc` `m`-attribute payloads. Returns [] if the grid never
    appears (no results, or a slow/blocked load past `timeout_ms`). Requires the
    `images` dependency group and `playwright install chromium`."""
    from playwright.sync_api import TimeoutError as PlaywrightTimeoutError

    url = SEARCH_URL.format(q=quote(term))
    context = _browser().new_context(user_agent=_USER_AGENT)
    context.add_cookies([_SAFESEARCH_COOKIE])  # SafeSearch off
    try:
        page = context.new_page()
        page.route("**/*", _block_media)
        page.goto(url, wait_until="domcontentloaded")
        try:
            page.wait_for_selector("a.iusc", timeout=timeout_ms)
        except PlaywrightTimeoutError:
            return []
        m_attrs = page.eval_on_selector_all(
            "a.iusc",
            "els => els.map(e => e.getAttribute('m')).filter(Boolean)",
        )
    finally:
        context.close()
    return parse_iusc(m_attrs)[:limit]
