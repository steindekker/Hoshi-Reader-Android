"""massif.la sentence search — the Sentence source for single-word mining.

No public API exists; this scrapes the result list. Each result is
`<li class="text-japanese">` whose first <div> is the sentence (query term
wrapped in <em>). Network/parse failures return [] so callers can fall back.
"""
from __future__ import annotations

import html
import re

import requests

from . import config

_LI = re.compile(r'<li class="text-japanese">\s*<div>(.*?)</div>', re.S)
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


def top_sentences(term: str, n: int = config.MINE_SENTENCE_N,
                  timeout: int = config.MASSIF_TIMEOUT) -> list[str]:
    """Top-n Massif example sentences for `term`, or [] on any failure."""
    try:
        resp = requests.get(config.MASSIF_URL, params={"q": term},
                            headers=_HEADERS, timeout=timeout)
        resp.raise_for_status()
        return parse(resp.text, n)
    except requests.RequestException:
        return []
