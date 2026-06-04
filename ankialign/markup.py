"""Pure string transforms for Japanese furigana / jidoujisho-JRP markup.

No Anki imports — unit-testable standalone.
"""
import re

# matches an Anki furigana segment: base[reading]
_RUBY = re.compile(r"([^\s\[\]<>]+)\[([^\]]+)\]")
_HAS_KANJI = re.compile(r"[㐀-鿿豈-﫿々\U00020000-\U0002A6DF\U0002A700-\U0002CEAF\U0002CEB0-\U0002EBEF]")
_AUDIO_EXT = re.compile(r"\.(mp3|ogg|wav|m4a|flac|aac|opus)$", re.I)
_IMG_EXT = re.compile(r"\.(jpe?g|png|gif|webp|avif|svg|bmp)$", re.I)


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


def entryfurigana_surface(s: str) -> str:
    """Anki EntryFurigana -> plain surface string."""
    return _anki_surface(s)


def entryfurigana_reading(s: str) -> str:
    """Anki EntryFurigana -> kana reading, or '' when bare kanji remains."""
    r = _anki_reading(s)
    return "" if _HAS_KANJI.search(r) else r


def as_sound(v: str) -> str:
    """Wrap a bare audio filename in [sound:...]; leave existing [sound:],
    empty, or non-audio values untouched. (jidoujisho Yuuna stores bare
    filenames.)"""
    s = v.strip()
    if not s or "[sound:" in s:
        return v
    return f"[sound:{s}]" if _AUDIO_EXT.search(s) else v


def as_image(v: str) -> str:
    """Wrap a bare image filename in <img src="...">; leave existing <img>,
    empty, or non-image values untouched."""
    s = v.strip()
    if not s or "<img" in s.lower():
        return v
    return f'<img src="{s}">' if _IMG_EXT.search(s) else v
