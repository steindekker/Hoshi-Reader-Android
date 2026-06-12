"""The Unit: one token's worth of data passed from tokenize to emit."""
from __future__ import annotations
from dataclasses import dataclass, field

@dataclass(frozen=True)
class Unit:
    surface: str        # text as it appears, possibly inflected: 見逃し
    lemma: str          # dictionary form: 見逃す
    reading: str        # hiragana reading of the surface: みのがし
    lemma_reading: str  # hiragana reading of the lemma (accent key): みのがす
    is_content: bool    # False -> rendered as bare text (particles, punctuation)
    is_yougen: bool     # 動詞/形容詞 -> emit ;Y
    accents: tuple[int, ...] = field(default=())  # () -> furigana only, no pitch block
    ambiguous: bool = False
    conj_computed: bool = False  # accents are the computed conjugated downstep on
                                 # `reading` itself -> emit ;Y<acc> with NO base
