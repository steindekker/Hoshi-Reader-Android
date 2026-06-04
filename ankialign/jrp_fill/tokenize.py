"""SudachiPy Mode C tokenization -> Unit list, with lemma-reading accent
lookup and a Mode C -> Mode A split-and-retry cascade.

Dict misses (~1%) are left with no accent (furigana only); they are mostly
function words / slang / proper nouns that don't need a pitch graph.
"""
from __future__ import annotations
from functools import lru_cache
from .unit import Unit
from .accent import AccentIndex
from ._utils import hira

_CONTENT = {"名詞", "代名詞", "動詞", "形容詞", "副詞", "形状詞", "連体詞", "感動詞"}
_YOUGEN = {"動詞", "形容詞"}

@lru_cache(maxsize=1)
def _tok():
    from sudachipy import dictionary
    return dictionary.Dictionary(dict="full").create()

@lru_cache(maxsize=1)
def _modes():
    from sudachipy import tokenizer as t
    return t.Tokenizer.SplitMode.A, t.Tokenizer.SplitMode.C

@lru_cache(maxsize=4096)
def _lemma_reading(lemma: str) -> str:
    _, C = _modes()
    ms = _tok().tokenize(lemma, C)
    return hira("".join(m.reading_form() or m.surface() for m in ms))

def _to_unit(m, acc: AccentIndex) -> Unit:
    pos0 = m.part_of_speech()[0]
    surface = m.surface()
    reading = hira(m.reading_form() or surface)
    lemma = m.dictionary_form() or surface
    is_content = pos0 in _CONTENT
    if not is_content:
        return Unit(surface, lemma, reading, reading, False, False, ())
    lemread = _lemma_reading(lemma)
    positions = acc.lookup(lemma, lemread) or acc.lookup(lemma, reading)
    return Unit(surface, lemma, reading, lemread, True, pos0 in _YOUGEN,
                tuple(positions) if positions else ())

def units(text: str, acc: AccentIndex) -> list[Unit]:
    A, C = _modes()
    out: list[Unit] = []
    for m in _tok().tokenize(text, C):
        u = _to_unit(m, acc)
        if u.is_content and not u.accents:
            subs = list(m.split(A))
            if len(subs) > 1:
                sub_us = [_to_unit(x, acc) for x in subs]
                if all((not su.is_content) or su.accents for su in sub_us):
                    out.extend(sub_us)
                    continue
        out.append(u)
    return out
