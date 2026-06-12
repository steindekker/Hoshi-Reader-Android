"""Pure string transforms for Japanese furigana / jidoujisho-JRP markup.

No Anki imports — unit-testable standalone.
"""
import re

# matches an Anki furigana segment: base[reading]
_RUBY = re.compile(r"([^\s\[\]<>]+)\[([^\]]+)\]")
_HAS_KANJI = re.compile(r"[㐀-鿿豈-﫿々\U00020000-\U0002A6DF\U0002A700-\U0002CEAF\U0002CEB0-\U0002EBEF]")


def _anki_surface(s: str) -> str:
    """Anki furigana notation -> surface (drop readings, join segments)."""
    return _RUBY.sub(r"\1", s).replace(" ", "")


def _anki_reading(s: str) -> str:
    """Anki furigana notation -> reading (readings replace their bases)."""
    return _RUBY.sub(r"\2", s).replace(" ", "")


def jrp_to_furigana(s: str) -> str:
    """jidoujisho/JRP markup -> Anki furigana notation, e.g.
    '{[一|ひと]つ;2}' -> '一[ひと]つ'."""
    def _unit(m: re.Match) -> str:
        return re.sub(r";[^;]*$", "", m.group(1))            # drop accent suffix (last ;…)
    s = re.sub(r"\{([^{}]*)\}", _unit, s)
    s = re.sub(r"\[([^\[\]|=]*)=([^\[\]]*)\]", r"\1", s)       # [surface=base] -> surface
    s = re.sub(r"\[([^\[\]|]+)\|([^\[\]]+)\]", r" \1[\2]", s)  # [漢字|かな] -> ' 漢字[かな]'
    return s.strip()


def jrp_surface(s: str) -> str:
    """JRP markup -> plain surface string."""
    return _anki_surface(jrp_to_furigana(s))


def jrp_reading(s: str) -> str:
    """Kana reading, or '' when bare kanji remains (no clean reading available)."""
    r = _anki_reading(jrp_to_furigana(s))
    return "" if _HAS_KANJI.search(r) else r
