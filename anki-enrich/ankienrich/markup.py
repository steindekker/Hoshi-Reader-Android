"""Pure string transforms for Japanese furigana / jidoujisho-JRP markup.

No Anki imports — unit-testable standalone.
"""
import re

import jaconv

# matches an Anki furigana segment: base[reading]
_RUBY = re.compile(r"([^\s\[\]<>]+)\[([^\]]+)\]")

_KANA = re.compile(r"[぀-ヿｦ-ﾟ]")          # hira + kata (+ half-width)
_FURI_SEG = re.compile(r"([^ >]+?)\[([^\]]+)\]")             # mirrors Anki's furigana: filter


def fix_furigana_spacing(s: str) -> str:
    """Insert the delimiter space Anki's `furigana:` filter needs before a
    base[reading] group, so a preceding kana particle isn't swallowed into the
    ruby base (`を逃[のが]` -> `を 逃[のが]`). Conservative: a leading-kana run is
    split off only when it is NOT a prefix of the reading — compounds whose kana
    IS read (`シャボン玉[しゃぼんだま]`) are left whole. Idempotent."""
    def _fix(m: re.Match) -> str:
        base, reading = m.group(1), m.group(2)
        i = 0
        while i < len(base) and _KANA.match(base[i]):
            i += 1
        lead, rest = base[:i], base[i:]
        if not lead or not rest:                            # all-kanji-led or all-kana: nothing to split
            return m.group(0)
        if jaconv.kata2hira(reading).startswith(jaconv.kata2hira(lead)):
            return m.group(0)                               # kana is part of the reading -> one group
        return f"{lead} {rest}[{reading}]"
    return _FURI_SEG.sub(_fix, s)

_BR = re.compile(r"<br\s*/?>", re.I)
_RT = re.compile(r"<rt\b[^>]*>.*?</rt>", re.I | re.S)   # duplicate reading: drop content too
_TAG = re.compile(r"</?[a-zA-Z][^>]*>")


def strip_sentence_html(s: str) -> str:
    """Reduce a Sentence field to JRP markup + bare text: <br> -> newline, the
    redundant ruby reading (<rt>…</rt>) dropped wholesale, every other tag
    unwrapped (contents kept). JRP `{…}`/`[…]` markup is left untouched."""
    s = _BR.sub("\n", s)
    s = _RT.sub("", s)
    return _TAG.sub("", s)


# Anime-caption speaker label: a parenthetical at the start of a line or right
# after sentence-final punctuation, e.g. （長嶺）/（エレン）. Two guards keep real
# text safe: an inline gloss always FOLLOWS its term (国際サッカー連盟（ＦＩＦＡ）,
# さん（28）), and the trailing-content lookahead (?=\S) spares a whole-sentence
# aside that IS the parenthetical (（…みたいだな）) — a speaker label is only ever a
# prefix to the line it labels.
_SPEAKER = re.compile(r"^[ 　]*[（(][^（）()]*[）)][ 　]*(?=\S)", re.M)
_AFTER_BREAK = re.compile(r"([。！？])[ 　]*[（(][^（）()]*[）)][ 　]*(?=\S)")


def strip_subtitle_labels(s: str) -> str:
    """Drop subtitle speaker labels (（name）) left by anime mining."""
    s = _AFTER_BREAK.sub(r"\1", s)
    return _SPEAKER.sub("", s)


def normalize_spaces(s: str) -> str:
    """Ideographic space (U+3000) -> ASCII space. Mined sentences (NHK news,
    subtitles, graded readers) use full-width spaces as phrase/headline breaks;
    collapse the glyph to a plain space so the card shows a normal-width gap and
    the field stays consistent with its cloze. Pitch is unaffected — the space is
    a non-content token either way."""
    return s.replace("　", " ")


# Kanji character class (CJK ideograph ranges + 々). Single source of truth;
# re-exported by ankienrich.jrp_fill.kana.
KANJI_RE = re.compile(r"[㐀-鿿豈-﫿々\U00020000-\U0002A6DF\U0002A700-\U0002CEAF\U0002CEB0-\U0002EBEF]")


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
    return "" if KANJI_RE.search(r) else r
