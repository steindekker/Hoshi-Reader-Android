"""Shared pure helpers for the jrp_fill package (kana normalization, kanji test)."""
from __future__ import annotations

import jaconv

# Single source of truth lives in markup.py; re-exported here so the jrp_fill
# package can `from .kana import KANJI_RE`.
from ankienrich.markup import KANJI_RE

__all__ = ["KANJI_RE", "hira"]


def hira(s: str) -> str:
    """Katakana -> hiragana (ー and non-katakana pass through; the katakana
    iteration marks ヽヾ become ゝゞ)."""
    return jaconv.kata2hira(s)
