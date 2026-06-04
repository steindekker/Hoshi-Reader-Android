"""Shared pure helpers for the jrp_fill package (kana normalization, kanji test)."""
from __future__ import annotations
import re

# Kanji character class (CJK ideograph ranges + 々). Matches ankialign/markup.py.
KANJI_RE = re.compile(
    r"[㐀-鿿豈-﫿々\U00020000-\U0002A6DF\U0002A700-\U0002CEAF"
    r"\U0002CEB0-\U0002EBEF]"
)
_KATA = {chr(c): chr(c - 0x60) for c in range(0x30A1, 0x30F7)}

def hira(s: str) -> str:
    """Katakana -> hiragana (other chars, incl. ー, pass through)."""
    return "".join(_KATA.get(c, c) for c in s)
