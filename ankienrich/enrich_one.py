"""Single-note enrichment: build the field set for one (term, reading) by reusing
each pass's per-note lookup. Used by the Telegram mining flow for BOTH the chat
preview and the committed card, so preview == written value. Pure reads (no col
mutation): audio bytes are returned, not written; the writer (mine.create_card)
persists them.
"""
from __future__ import annotations

import html as _html
import re
from dataclasses import dataclass

from .defs import index as defs_index
from .defs import enrich as defs_enrich
from .examples import enrich as ex_enrich
from .audio import enrich as audio_enrich
from . import pitch as _pitch, pitch_svg as _pitch_svg, markup as _markup, cloze as _cloze
from .jrp_fill.align import furigana_segments

_TAG = re.compile(r"<[^>]+>")


@dataclass(frozen=True)
class Candidate:
    reading: str
    gloss: str  # short plain-text gloss for the picker button/label
    freq: int | None = None   # JPDB rank, or None when unknown


def term_furigana(term: str, reading: str) -> str:
    """Anki furigana notation, e.g. ('部活','ぶかつ') -> '部活[ぶかつ]'; kana-only plain."""
    segs = furigana_segments(term, reading)
    return "".join(f"{b}[{r}]" if r else b for b, r in segs)


def _plain_gloss(rows) -> str:
    """First sense's text, tags stripped, clipped — for the disambiguation label."""
    if not rows:
        return ""
    text = _html.unescape(_TAG.sub("", rows[0]["html"] or "")).strip()
    return text[:40]


def candidates(defs_conn, term: str, *, freq_conn=None) -> list[Candidate]:
    """Distinct readings for `term` across the dict index, each with a 1-line gloss
    and (when `freq_conn` is given) a JPDB rank. Sorted most-frequent first;
    unknown-rank readings sort last. Empty list = unknown word (caller refuses)."""
    jj, je = defs_index.lookup(defs_conn, term)
    by_reading: dict[str, list] = {}
    for r in (*je, *jj):  # prefer JE gloss for the label
        # Some J-J dicts store kana headwords (e.g. パスタ) reading-less; an empty
        # reading means "same as the headword", so fold it into the term's group
        # rather than spawning a junk empty-reading candidate.
        by_reading.setdefault(r["reading"] or term, []).append(r)
    out = []
    for reading, rows in by_reading.items():
        freq = None
        if freq_conn is not None:
            from .freq import index as freq_index
            hit = freq_index.lookup(freq_conn, term, reading)
            freq = hit[0] if hit else None
        out.append(Candidate(reading=reading, gloss=_plain_gloss(rows), freq=freq))
    out.sort(key=lambda c: (c.freq is None, c.freq or 0))
    return out


@dataclass(frozen=True)
class Fields:
    term: str
    reading: str
    furigana: str
    pitch_svg: str
    meaning: str
    definition_source: str
    examples: str
    audio_pick: audio_enrich.Pick | None
    audio_clip: bytes | None
    accents: tuple[int, ...] = ()     # downstep positions (0 = heiban)
    freq: int | None = None           # JPDB rank, or None

    @property
    def has_definition(self) -> bool:
        return bool(self.meaning)


@dataclass(frozen=True)
class SentenceFields:
    sentence: str            # JRP markup
    sentence_furigana: str   # Anki furigana notation
    cloze_before: str
    cloze_inside: str
    cloze_after: str


def fields_for_sentence(term: str, reading: str, plain_sentence: str, *,
                        generate, acc) -> SentenceFields:
    """Markup + furigana + cloze for a chosen plain sentence. `generate` is the
    JRP generator (jrp_fill.make_generator()); `acc` an AccentIndex for cloze
    tokenisation (may be None for the verbatim-find fast path)."""
    sentence = generate(plain_sentence)
    furi = _markup.jrp_to_furigana(sentence)
    plain = _cloze.strip_markup(sentence).strip()
    # verbatim fast path never needs acc; skip tokenized path when acc is absent
    pos = plain.find(term) if term else -1
    if pos != -1:
        split: tuple[str, str, str] | None = (plain[:pos], term, plain[pos + len(term):])
    elif acc is not None:
        split = _cloze.locate(term, reading, plain, acc)
    else:
        split = None
    before, inside, after = split if split else ("", "", "")
    return SentenceFields(sentence=sentence, sentence_furigana=furi,
                          cloze_before=before, cloze_inside=inside,
                          cloze_after=after)


def build_fields(term: str, reading: str, *, defs_conn, ex_conn, audio_conn,
                 acc, freq_conn=None) -> Fields:
    """Compute every card field derivable from (term, reading). Pure read."""
    meaning, source, _kind = defs_enrich.definition_for(defs_conn, term, reading)
    examples = ex_enrich.examples_for(ex_conn, term, reading)

    accents = _pitch.accents_for(acc, term, reading)
    pitch = _pitch_svg.render_multi(reading, accents) if accents else ""

    pick = audio_enrich.lookup(audio_conn, term, reading)
    clip = audio_enrich.read_clip(audio_conn, pick) if pick else None
    if clip is None:
        pick = None

    freq = None
    if freq_conn is not None:
        from .freq import index as freq_index
        hit = freq_index.lookup(freq_conn, term, reading)
        freq = hit[0] if hit else None

    return Fields(
        term=term, reading=reading, furigana=term_furigana(term, reading),
        pitch_svg=pitch, meaning=meaning, definition_source=source,
        examples=examples, audio_pick=pick, audio_clip=clip,
        accents=tuple(accents or ()), freq=freq)
