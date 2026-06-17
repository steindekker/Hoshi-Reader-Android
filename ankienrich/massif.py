"""massif.la sentence search — the Sentence source for single-word mining.

No public API exists; this scrapes the result list. Each result is
`<li class="text-japanese">` whose first <div> is the sentence (query term
wrapped in <em>). Network/parse failures return [] so callers can fall back.
"""
from __future__ import annotations

import atexit
import concurrent.futures
import html
import re

import requests

from . import config

_LI = re.compile(r'<li\b[^>]*\bclass="text-japanese"[^>]*>\s*<div>(.*?)</div>', re.S)
_TAG = re.compile(r"<[^>]+>")
_HEADERS = {"User-Agent": "Mozilla/5.0 (anki-enrich)"}


def parse(body: str, n: int) -> list[str]:
    out = []
    for frag in _LI.findall(body):
        text = html.unescape(_TAG.sub("", frag)).strip()
        if text:
            out.append(text)
        if len(out) >= n:
            break
    return out


def _fetch(term: str, n: int, timeout: int) -> list[str]:
    """The actual HTTP fetch + parse. [] on any network failure."""
    try:
        resp = requests.get(config.MASSIF_URL, params={"q": term},
                            headers=_HEADERS, timeout=timeout)
        resp.raise_for_status()
        return parse(resp.text, n)
    except requests.RequestException:
        return []


# --- Optional background prefetch ----------------------------------------------
#
# top_sentences is one HTTP GET, but firing it at preview-time — while the user
# reads the card — means the list is already in hand the moment they tap "Pick
# sentence". Same overlap trick images.py uses for the (slower) Bing search, but
# far lighter: a lazy 1-slot executor, no warm browser. Keyed by (term, n) so a
# prefetch only satisfies a matching request.
_EXECUTOR: concurrent.futures.ThreadPoolExecutor | None = None
_PREFETCH: dict[tuple[str, int], concurrent.futures.Future] = {}


def _executor() -> concurrent.futures.ThreadPoolExecutor:
    global _EXECUTOR
    if _EXECUTOR is None:
        _EXECUTOR = concurrent.futures.ThreadPoolExecutor(
            max_workers=1, thread_name_prefix="massif")
    return _EXECUTOR


def prefetch(term: str, *, n: int = config.MINE_SENTENCE_N,
             timeout: int = config.MASSIF_TIMEOUT) -> None:
    """Kick off a fetch for `term` in the background so `top_sentences(term)`
    later returns (near-)instantly. No-op if one is already in flight."""
    key = (term, n)
    if key in _PREFETCH:
        return
    if len(_PREFETCH) > 8:          # bound abandoned-flow leakage (single user)
        _PREFETCH.clear()
    _PREFETCH[key] = _executor().submit(_fetch, term, n, timeout)


def top_sentences(term: str, n: int = config.MINE_SENTENCE_N,
                  timeout: int = config.MASSIF_TIMEOUT) -> list[str]:
    """Top-n Massif example sentences for `term`, or [] on any failure. Consumes a
    prefetch()ed fetch if one is in flight (keyed on term+n), else fetches now."""
    fut = _PREFETCH.pop((term, n), None)
    if fut is not None:
        return fut.result()
    return _fetch(term, n, timeout)


def shutdown() -> None:
    """Tear down the prefetch executor. Best-effort, for clean process exit."""
    global _EXECUTOR
    if _EXECUTOR is not None:
        _EXECUTOR.shutdown(wait=False, cancel_futures=True)
        _EXECUTOR = None
    _PREFETCH.clear()


atexit.register(shutdown)
