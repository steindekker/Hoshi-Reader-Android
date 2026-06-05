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

# Common-reading preference for words the dict lists under MULTIPLE readings, so
# the dict oracle can't disambiguate and Sudachi picks the rarer one. Keyed on
# the exact whole surface (never fires inside a compound: 私立=しりつ untouched).
# Reserve this only for genuine frequency calls; wrong single readings the dict
# *can* settle (綾->あや) are handled by the oracle below, not here.
_READING_OVERRIDE = {"私": "わたし"}

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

def _dict_reading(lemma: str, acc: AccentIndex) -> tuple[str, list[int]] | None:
    """If the dict knows exactly one reading for this word, return it. Lets the
    dict correct a reading Sudachi got wrong (綾: Sudachi りょう, dict only あや)
    without per-word hardcoding. Ambiguous words (私: わたし & わたくし) -> None."""
    known = acc.readings_for(lemma)
    if len(known) == 1:
        reading, positions = next(iter(known.items()))
        return reading, positions
    return None

def _to_unit(m, acc: AccentIndex) -> Unit:
    pos0 = m.part_of_speech()[0]
    surface = m.surface()
    reading = _READING_OVERRIDE.get(surface) or hira(m.reading_form() or surface)
    lemma = m.dictionary_form() or surface
    is_content = pos0 in _CONTENT
    if not is_content or _is_bound_aux(pos0, surface, lemma):
        return Unit(surface, lemma, reading, reading, False, False, ())
    lemread = _READING_OVERRIDE.get(surface) or _lemma_reading(lemma)
    positions = acc.lookup(lemma, lemread) or acc.lookup(lemma, reading)
    # dict oracle: non-inflecting word whose Sudachi reading the dict doesn't
    # know -> adopt the dict's reading if it's unambiguous (fixes 綾->あや).
    if not positions and pos0 not in _YOUGEN and surface not in _READING_OVERRIDE:
        oracle = _dict_reading(lemma, acc)
        if oracle is not None:
            reading = lemread = oracle[0]
            positions = oracle[1]
    return Unit(surface, lemma, reading, lemread, True, pos0 in _YOUGEN,
                tuple(positions) if positions else ())

def _is_aux_tail(m) -> bool:
    """A morpheme that continues the 用言: auxiliaries (た, ない, ます, れる, させ...),
    bound 形容詞/動詞 — the negative ない and 補助動詞 いる/くる/おく/しまう... — and the
    て/で connector that links them (生きて+きた, 祀って+いる). So the whole conjugated
    form (生きてきた, 祀っている) becomes one unit."""
    pos = m.part_of_speech()
    if pos[0] == "助動詞":
        return True
    if pos[0] == "助詞" and pos[1] == "接続助詞" and m.surface() in ("て", "で"):
        return True
    return pos[0] in _YOUGEN and pos[1] == "非自立可能"

_DERIV = {"れる", "られる", "せる", "させる"}  # passive/potential/causative

def _conj_downstep(head, tail, heiban: bool, dict_acc: int,
                   lemread: str, conj_reading: str) -> int | None:
    """Classify the conjugation (outer form + derivation) from the morpheme chain
    and compute the real downstep, or None to fall back to the dict-form base.
    Conservative: only clean, common patterns; anything stacked/unsupported
    (たい, ました, ません, です, て/ば not absorbed...) -> None."""
    from . import conjugate as C
    lemmas = [t.dictionary_form() or t.surface() for t in tail]
    # only fire on inflectional auxiliaries with validated rules; anything else
    # (べき, よう, そう, らしい, みたい, ず, ん/ぬ, です...) -> fall back to dict-form
    _OK = {"た", "だ", "ない", "ます", "れる", "られる", "せる", "させる"}
    if any(l not in _OK for l in lemmas):
        return None
    if "ます" in lemmas and lemmas[-1] != "ます":  # ました / ません
        return None
    last = lemmas[-1]
    prev = lemmas[-2] if len(lemmas) >= 2 else None
    if last in ("た", "だ"):
        form = "nakatta" if prev == "ない" else "ta"
    elif last == "ない":
        form = "nai"
    elif last == "ます":
        form = "masu"
    elif last in _DERIV:
        form = "dict"            # derived verb in plain present (断られる)
    else:
        return None
    if head.part_of_speech()[0] == "形容詞":
        if any(l in _DERIV for l in lemmas):
            return None
        return C.adj_downstep(heiban, form, C.count_moras(lemread) - 1)
    derived = any(l in _DERIV for l in lemmas)
    atamadaka = dict_acc == 1 and not derived
    return C.verb_downstep(heiban, atamadaka, form, C.count_moras(conj_reading))

def _merge_yougen(ms, i: int, acc: AccentIndex,
                  conj_accent: bool = False) -> tuple[Unit, int] | None:
    """Fuse a 動詞/形容詞 head with its trailing inflection chain into ONE yougen
    unit (分から+なかっ+た -> 分からなかった). The accent stays the dictionary-form
    downstep; emit.py renders the dict reading as the JRP base (`|わかる`) so the
    pitch graph is correct while the conjugated surface shows kifuku coloring.
    Returns None (caller uses the per-morpheme path) unless the head is a real
    content yougen whose lemma has a known accent -> never loses coverage."""
    head = ms[i]
    pos0 = head.part_of_speech()[0]
    surface = head.surface()
    lemma = head.dictionary_form() or surface
    if pos0 not in _YOUGEN or _is_bound_aux(pos0, surface, lemma):
        return None
    j = i + 1
    while j < len(ms) and _is_aux_tail(ms[j]):
        j += 1
    if j == i + 1:
        return None  # bare verb, no tail -> let the per-morpheme path handle it
    surf = "".join(x.surface() for x in ms[i:j])
    read = "".join(hira(x.reading_form() or x.surface()) for x in ms[i:j])
    # lexicalized whole form (思わず, 我ながら...): the inflected span is itself a
    # dict headword, so use its OWN accent as a plain unit, not the verb's. The
    # dict already knows it -> no hardcoding, and it isn't a 用言 (no Y).
    whole = acc.lookup(surf, read)
    if whole is not None:
        return Unit(surf, surf, read, read, True, False, tuple(whole)), j - i
    lemread = _READING_OVERRIDE.get(surface) or _lemma_reading(lemma)
    positions = acc.lookup(lemma, lemread) or \
        acc.lookup(lemma, hira(head.reading_form() or surface))
    if not positions:
        return None
    if conj_accent:
        ds = _conj_downstep(head, ms[i + 1:j], positions[0] == 0, positions[0],
                            lemread, read)
        if ds is not None:
            # computed downstep sits on the conjugated reading itself -> no base
            return Unit(surf, lemma, read, read, True, True, (ds,),
                        conj_computed=True), j - i
    return Unit(surf, lemma, read, lemread, True, True, tuple(positions)), j - i

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

def units(text: str, acc: AccentIndex, conj_accent: bool = False) -> list[Unit]:
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
        yg = _merge_yougen(ms, i, acc, conj_accent)
        if yg is not None:
            unit, consumed = yg
            out.append(unit)
            i += consumed
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
