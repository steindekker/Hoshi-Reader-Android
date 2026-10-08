"""SudachiPy Mode C tokenization -> Unit list, with lemma-reading accent
lookup and a Mode C -> Mode A split-and-retry cascade.

Dict misses (~1%) are left with no accent (furigana only); they are mostly
function words / slang / proper nouns that don't need a pitch graph.

The linguistic data (POS sets, stoplists) lives in lexicon.py.
"""
from __future__ import annotations
from functools import lru_cache
from .unit import Unit
from .accent import AccentIndex
from .kana import hira, KANJI_RE
from .lexicon import (
    AFFIX_POS, CONTENT_POS, DERIV_SUFFIXES, HON_PREFIXES, KOSOADO, LIGHT_LEMMAS,
    NO_ABSORB, NO_FURIGANA, NO_PITCH_FORMS, NO_PITCH_LEMMAS, NOMINAL_POS,
    PERSON_HONORIFICS, READING_OVERRIDE, YOUGEN_POS,
)


def _is_bound_aux(pos0: str, surface: str, lemma: str) -> bool:
    # single-mora kana verb fragment (し, い, さ, き...) — always a conjugation
    # artifact, never a standalone word worth a pitch graph
    if pos0 == "動詞" and len(surface) == 1 and "぀" <= surface <= "ヿ":
        return True
    return lemma in LIGHT_LEMMAS

@lru_cache(maxsize=1)
def _tok():
    from sudachipy import dictionary
    return dictionary.Dictionary(dict="full").create()

@lru_cache(maxsize=1)
def _modes():
    from sudachipy import tokenizer as t
    return t.Tokenizer.SplitMode.A, t.Tokenizer.SplitMode.C

@lru_cache(maxsize=4096)
def _lemma_reading(word: str) -> str:
    """Re-tokenize a word in isolation -> hira reading. Isolating it fixes Sudachi's
    context-dependent mis-reads (香 reads かおり inside お香 but こう alone), so a lemma
    or an honorific stem looked up on its own gets the right reading."""
    _, C = _modes()
    ms = _tok().tokenize(word, C)
    return hira("".join(m.reading_form() or m.surface() for m in ms))

def _join_surface_reading(morphemes) -> tuple[str, str]:
    """Joined surface and joined hira reading over a span of morphemes."""
    surf = "".join(x.surface() for x in morphemes)
    read = hira("".join(x.reading_form() or x.surface() for x in morphemes))
    return surf, read

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
    pos = m.part_of_speech()
    pos0 = pos[0]
    surface = m.surface()
    ov = READING_OVERRIDE.get(surface)
    reading = ov or hira(m.reading_form() or surface)
    lemma = m.dictionary_form() or surface
    is_content = pos0 in CONTENT_POS
    if not is_content or _is_bound_aux(pos0, surface, lemma):
        return Unit(surface, lemma, reading, reading, False, False, ())
    # modal stems (evidential/likeness よう, みたい = 形状詞/助動詞語幹) are
    # grammatical -> no pitch; reading-aware drops for polysemous kanji formal nouns
    if (lemma in NO_PITCH_LEMMAS or pos[1] == "助動詞語幹"
            or (lemma, reading) in NO_PITCH_FORMS):  # keep furigana, drop pitch
        if lemma in NO_FURIGANA:                     # ...and drop the reading too
            reading = surface
        return Unit(surface, lemma, reading, reading, True, False, ())
    lemread = ov or _lemma_reading(lemma)
    positions = acc.lookup(lemma, lemread) or acc.lookup(lemma, reading)
    # dict oracle: non-inflecting word whose Sudachi reading the dict doesn't
    # know -> adopt the dict's reading if it's unambiguous (fixes 綾->あや).
    if not positions and pos0 not in YOUGEN_POS and ov is None:
        oracle = _dict_reading(lemma, acc)
        if oracle is not None:
            reading = lemread = oracle[0]
            positions = oracle[1]
    return Unit(surface, lemma, reading, lemread, True, pos0 in YOUGEN_POS,
                tuple(positions) if positions else ())

def _is_aux_tail(m) -> bool:
    """A morpheme that continues the 用言: auxiliaries (た, ない, ます, れる, させ...),
    bound 形容詞/動詞 — the negative ない and 補助動詞 いる/くる/おく/しまう... — and the
    て/で connector that links them (生きて+きた, 祀って+いる). So the whole conjugated
    form (生きてきた, 祀っている) becomes one unit."""
    pos = m.part_of_speech()
    if pos[0] == "助動詞":
        return (m.dictionary_form() or "") not in NO_ABSORB
    if pos[0] == "助詞" and pos[1] == "接続助詞" and m.surface() in ("て", "で", "ば"):
        return True
    return pos[0] in YOUGEN_POS and pos[1] == "非自立可能"

def _conj_downstep(head, tail, heiban: bool, dict_acc: int,
                   lemread: str, conj_reading: str) -> int | None:
    """Classify the conjugation (outer form + derivation) from the morpheme chain
    and compute the real downstep, or None to fall back to the dict-form base.
    Conservative: only clean, common patterns; anything stacked/unsupported
    (たい, ました, ません, です, て/ば not absorbed...) -> None."""
    from . import conjugate as C
    lemmas = [t.dictionary_form() or t.surface() for t in tail]
    m = C.count_moras(conj_reading)
    last = lemmas[-1]
    prev = lemmas[-2] if len(lemmas) >= 2 else None
    # ます / ません place the downstep on their own penult regardless of what comes
    # before, so they're safe even after て+補助動詞 (教えていただけませ\ん, ありませ\ん)
    if last == "ます" or (last == "ぬ" and "ます" in lemmas):
        return m - 1
    if last == "た" and prev == "ます":      # ました polite past -> downstep on ま
        return max(1, m - 2)
    # negative conditional 〜なければ: downstep on the な (before ければ)
    if last == "ば" and prev == "ない":
        return max(1, m - 3)
    # other endings: only clean inflectional chains; reject て/補助動詞/modals so we
    # don't mis-accent (べき, よう, ましょう, ている-as-past...) -> dict-form fallback
    _OK = {"た", "だ", "ない", "ば", "れる", "られる", "せる", "させる"}
    if any(lem not in _OK for lem in lemmas):
        return None
    if last == "ば":
        form = "ba"
    elif last in ("た", "だ"):
        form = "nakatta" if prev == "ない" else "ta"
    elif last == "ない":
        form = "nai"
    elif last in DERIV_SUFFIXES:
        form = "dict"            # derived verb in plain present (断られる)
    else:
        return None
    derived = any(lem in DERIV_SUFFIXES for lem in lemmas)
    if head.part_of_speech()[0] == "形容詞":
        if derived:
            return None
        return C.adj_downstep(heiban, form, C.count_moras(lemread) - 1)
    atamadaka = dict_acc == 1 and not derived
    return C.verb_downstep(heiban, atamadaka, form, m)

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
    if pos0 not in YOUGEN_POS or _is_bound_aux(pos0, surface, lemma):
        return None
    j = i + 1
    while j < len(ms) and _is_aux_tail(ms[j]):
        j += 1
    if j == i + 1:
        return None  # bare verb, no tail -> let the per-morpheme path handle it
    surf, read = _join_surface_reading(ms[i:j])
    if lemma in NO_PITCH_LEMMAS:  # 言った etc. -> furigana, no pitch
        return Unit(surf, lemma, read, read, True, False, ()), j - i
    # lexicalized whole form (思わず, 我ながら...): the inflected span is itself a
    # dict headword, so use its OWN accent as a plain unit, not the verb's. The
    # dict already knows it -> no hardcoding, and it isn't a 用言 (no Y).
    whole = acc.lookup(surf, read)
    if whole is not None:
        return Unit(surf, surf, read, read, True, False, tuple(whole)), j - i
    lemread = READING_OVERRIDE.get(surface) or _lemma_reading(lemma)
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
    js, jr = _join_surface_reading((m, nxt))
    pos = acc.lookup(js, jr)
    return Unit(js, js, jr, jr, True, False, tuple(pos)) if pos else None

def _kosoado_iu_merge(m, nxt, acc: AccentIndex) -> Unit | None:
    """こう/そう/ああ/どう + いう -> one 連体詞 block (そういう, こういう...). One block
    even if the dict lacks it; pitched when present (そういう[0], どういう[1])."""
    if m.surface() not in KOSOADO or nxt.part_of_speech()[0] != "動詞":
        return None
    if (nxt.dictionary_form() or "") not in ("いう", "言う"):
        return None
    js, jr = _join_surface_reading((m, nxt))
    pos = acc.lookup(js, jr)
    return Unit(js, js, jr, jr, True, False, tuple(pos) if pos else ())

def _prefix_merge(ms, i: int, acc: AccentIndex,
                  conj_accent: bool) -> tuple[Unit, int] | None:
    """お/ご + content that Sudachi split (お話, お出まし, ご相談) -> ONE unit, but
    only when the joined (surface, reading) is a dict headword (self-validating,
    so the dict's whole-word accent wins: おはなし[0], not the split はなし[3]). The result
    is a plain noun (no 用言 marking) even if it wraps a verb stem (お出まし)."""
    m = ms[i]
    if m.part_of_speech()[0] != "接頭辞" or m.surface() not in HON_PREFIXES or i + 1 >= len(ms):
        return None
    pre_s = m.surface()
    pre_r = hira(m.reading_form() or pre_s)
    yg = _merge_yougen(ms, i + 1, acc, conj_accent)
    if yg is not None:
        follow, n = yg
        fs, fr = follow.surface, follow.reading
    else:
        follow = _to_unit(ms[i + 1], acc)
        fs, fr, n = follow.surface, follow.reading, 1
    js, jr = pre_s + fs, pre_r + fr
    pos = acc.lookup(js, jr)
    if pos:
        return Unit(js, js, jr, jr, True, False, tuple(pos)), 1 + n
    return None

_IDIOM_MAX = 8  # phrasal idioms rarely run past a handful of morphemes

def _idiom_merge(ms, i: int, acc: AccentIndex) -> tuple[Unit, int] | None:
    """Fuse a particle-containing phrase whose whole SURFACE is a dict headword into
    ONE unit (目に余る -> めにあまる[1,4]). The reading-keyed merges can't reach these:
    none of them crosses a 助詞. Longest span first, gated so it only fires on a real
    verb phrase — ends in a 用言 AND carries an interior 助詞 — which excludes the
    こと/もの formal-noun class and the noun+の coincidence (本の != the 連体詞 ほんの).
    Uses the DICT reading and only when it's unambiguous, so a Sudachi mis-read on a
    sub-morpheme can never leak into the result."""
    if ms[i].part_of_speech()[0] not in CONTENT_POS:
        return None
    hi = min(i + _IDIOM_MAX, len(ms))
    for j in range(hi, i + 2, -1):       # >= 3 morphemes: head + 助詞 + 用言
        if ms[j - 1].part_of_speech()[0] not in YOUGEN_POS:
            continue
        if not any(ms[k].part_of_speech()[0] == "助詞" for k in range(i + 1, j - 1)):
            continue
        surf = "".join(x.surface() for x in ms[i:j])
        ds = _dict_reading(surf, acc)
        if ds is not None:
            return Unit(surf, surf, ds[0], ds[0], True, False, tuple(ds[1])), j - i
    return None

def _nominal_merge(ms, i: int, acc: AccentIndex) -> tuple[Unit, int] | None:
    """Fuse adjacent nominal morphemes Sudachi over-split. Two triggers, longest
    window first: (a) the joined surface+reading is a dict headword -> pitched with
    its accent (我が国 -> わがくに[1]); (b) the run carries an honorific 接頭辞 (お/ご)
    -> kept whole even on a dict miss, furigana-only (おばあちゃん). Verb stems stop
    the window, so お出まし stays for _prefix_merge."""
    n = len(ms)
    if ms[i].part_of_speech()[0] not in (NOMINAL_POS | {"接頭辞"}):
        return None
    j = i
    while j < n and ms[j].part_of_speech()[0] in (NOMINAL_POS | AFFIX_POS):
        j += 1
    while j - i >= 2:
        surf, read = _join_surface_reading(ms[i:j])
        pos = acc.lookup(surf, read) or acc.by_reading(read)
        if pos:
            return Unit(surf, surf, read, read, True, False, tuple(pos)), j - i
        has_prefix = any(ms[k].part_of_speech()[0] == "接頭辞" for k in range(i, j))
        # surface IS a dict headword but Sudachi mis-read a sub-morpheme, so the
        # reading-keyed lookups missed (天下一品: てんかいち+ひん != てんかいっぴん).
        # Adopt the dict's own reading + accent when it's unambiguous. Skip 接頭辞
        # spans: the honorific branch below owns those (お酒 is おさけ here, not the
        # dict's rare お酒=ごしゅ headword).
        if not has_prefix:
            ds = _dict_reading(surf, acc)
            if ds is not None:
                return Unit(surf, surf, ds[0], ds[0], True, False, tuple(ds[1])), j - i
        if has_prefix:
            rd, accents = read, ()
            pre = ms[i]
            # honorific お/ご compound the dict omits = transparent 美化語 -> 平板 (0),
            # but only when the stem is itself a known dict word (so we trust the
            # reading and skip novel compounds / 〜ちゃん diminutives), and not a
            # person-honorific (those keep their own accent).
            if (pre.part_of_speech()[0] == "接頭辞" and pre.surface() in HON_PREFIXES):
                stem = surf[len(pre.surface()):]
                sr = _lemma_reading(stem) if stem else ""
                if (stem and stem not in PERSON_HONORIFICS
                        and (acc.lookup(stem, sr) is not None or acc.readings_for(stem))):
                    rd = hira(pre.reading_form() or pre.surface()) + sr
                    accents = (0,)
            return Unit(surf, surf, rd, rd, True, False, accents), j - i
        j -= 1
    return None

def _resolve_dict_miss(u: Unit, m, mode_a, acc: AccentIndex) -> list[Unit] | None:
    """A content unit Sudachi kept whole but the accent dict misses: try to recover
    a pitch. Returns the replacement unit(s), or None to keep `u` as furigana-only.

    First trust Mode C — if the exact reading is an unambiguous dict homophone
    (開放感/解放感 -> かいほうかん[3]) keep the word whole with that accent. Otherwise
    fall back to a Mode A re-split, but only when every sub-unit is itself
    non-content or accented (so the split never drops a content word's pitch)."""
    if (not u.is_yougen and KANJI_RE.search(u.surface)
            and u.lemma not in NO_PITCH_LEMMAS
            and (u.lemma, u.reading) not in NO_PITCH_FORMS):
        rb = acc.by_reading(u.reading)
        if rb is not None:
            return [Unit(u.surface, u.lemma, u.reading, u.reading,
                         True, False, tuple(rb))]
    subs = list(m.split(mode_a))
    if len(subs) > 1:
        sub_us = [_to_unit(x, acc) for x in subs]
        if all((not su.is_content) or su.accents for su in sub_us):
            return sub_us
    return None

def units(text: str, acc: AccentIndex, conj_accent: bool = False) -> list[Unit]:
    A, C = _modes()
    ms = list(_tok().tokenize(text, C))
    out: list[Unit] = []
    i = 0
    while i < len(ms):
        m = ms[i]
        if i + 1 < len(ms):
            merged = _counter_merge(m, ms[i + 1], acc) or \
                _kosoado_iu_merge(m, ms[i + 1], acc)
            if merged is not None:
                out.append(merged)
                i += 2
                continue
        im = _idiom_merge(ms, i, acc)
        if im is not None:
            unit, consumed = im
            out.append(unit)
            i += consumed
            continue
        nm = _nominal_merge(ms, i, acc)
        if nm is not None:
            unit, consumed = nm
            out.append(unit)
            i += consumed
            continue
        pre = _prefix_merge(ms, i, acc, conj_accent)
        if pre is not None:
            unit, consumed = pre
            out.append(unit)
            i += consumed
            continue
        yg = _merge_yougen(ms, i, acc, conj_accent)
        if yg is not None:
            unit, consumed = yg
            out.append(unit)
            i += consumed
            continue
        u = _to_unit(m, acc)
        if u.is_content and not u.accents:
            replacement = _resolve_dict_miss(u, m, A, acc)
            if replacement is not None:
                out.extend(replacement)
                i += 1
                continue
        out.append(u)
        i += 1
    return out
