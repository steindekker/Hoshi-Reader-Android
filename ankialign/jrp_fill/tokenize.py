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
# Light/auxiliary verbs Sudachi peels off fixed expressions (もしかしたら,
# かもしれない) and て-form chains. Pitching their bound fragments (し, い, しれ)
# looks wrong and never appears in the user's accepted gold. Multi-mora real
# auxiliaries (しまう, くれる, full いる, できる, なる...) are unaffected.
_LIGHT_LEMMAS = {"する", "しれる"}

def _is_bound_aux(pos0: str, surface: str, lemma: str) -> bool:
    # single-mora kana verb fragment (し, い, さ, き...) — always a conjugation
    # artifact, never a standalone word worth a pitch graph
    if pos0 == "動詞" and len(surface) == 1 and "぀" <= surface <= "ヿ":
        return True
    return lemma in _LIGHT_LEMMAS

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
    if not is_content or _is_bound_aux(pos0, surface, lemma):
        return Unit(surface, lemma, reading, reading, False, False, ())
    lemread = _lemma_reading(lemma)
    positions = acc.lookup(lemma, lemread) or acc.lookup(lemma, reading)
    return Unit(surface, lemma, reading, lemread, True, pos0 in _YOUGEN,
                tuple(positions) if positions else ())

def _counter_merge(m, nxt, acc: AccentIndex) -> Unit | None:
    """Sudachi splits some number/noun + 接尾辞 compounds (数日 -> 数+日). If the
    joined (surface, reading) is in the accent dict, pitch it as one unit. This
    self-validates: 数日->すうじつ hits (correct), 二日->ふたか misses (stays safe,
    never producing a wrong reading)."""
    if nxt.part_of_speech()[0] != "接尾辞":
        return None
    js = m.surface() + nxt.surface()
    jr = hira((m.reading_form() or m.surface()) + (nxt.reading_form() or nxt.surface()))
    pos = acc.lookup(js, jr)
    return Unit(js, js, jr, jr, True, False, tuple(pos)) if pos else None

def units(text: str, acc: AccentIndex) -> list[Unit]:
    A, C = _modes()
    ms = list(_tok().tokenize(text, C))
    out: list[Unit] = []
    i = 0
    while i < len(ms):
        m = ms[i]
        if i + 1 < len(ms):
            merged = _counter_merge(m, ms[i + 1], acc)
            if merged is not None:
                out.append(merged)
                i += 2
                continue
        u = _to_unit(m, acc)
        if u.is_content and not u.accents:
            subs = list(m.split(A))
            if len(subs) > 1:
                sub_us = [_to_unit(x, acc) for x in subs]
                if all((not su.is_content) or su.accents for su in sub_us):
                    out.extend(sub_us)
                    i += 1
                    continue
        out.append(u)
        i += 1
    return out
