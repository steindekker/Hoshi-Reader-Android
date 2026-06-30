"""Backfill empty Cloze Before/Inside/After on jidoujisho Kinomoto notes.

The cloze fields hold the PLAIN (markup-stripped) sentence split around the mined
Term: Before + Inside + After == plain sentence, where Inside is the term as it
surfaces. For notes with a Sentence but no cloze we reconstruct it:

  1. strip JRP markup -> plain surface.
  2. locate the term span: verbatim (nouns); else the tokenized unit whose lemma
     == Term (inflected); else the content unit whose reading == Term's Reading
     (orthographic variants). Offsets come from the tokenization, so the split is
     exact even for inflected / repeated terms.
  3. write the three fields. Never overwrites a note that already has cloze.
"""
from __future__ import annotations

import re

from . import config, markup
from .collection import notetype_search
from .jrp_fill.accent import AccentIndex
from .jrp_fill.kana import hira
from .jrp_fill.tokenize import units

_FURI = re.compile(r"\[([^|\]]+)\|([^\]]+)\]")


def _spans(s: str):
    i, n, buf = 0, len(s), []
    while i < n:
        c = s[i]
        close = "}" if c == "{" else ("]" if c == "[" else None)
        if close:
            j = s.find(close, i)
            if j >= 0:
                if buf:
                    yield "plain", "".join(buf)
                    buf = []
                yield "markup", s[i:j + 1]
                i = j + 1
                continue
        buf.append(c)
        i += 1
    if buf:
        yield "plain", "".join(buf)


def strip_markup(sentence: str) -> str:
    out = []
    for kind, text in _spans(sentence):
        if kind == "markup":
            body = text[1:-1].partition(";")[0] if text.startswith("{") else text
            out.append(_FURI.sub(lambda m: m.group(1), body))
        else:
            out.append(text)
    # keep <br> and bidi marks so Before+Inside+After reconstructs byte-for-byte;
    # normalize the ideographic space to match the (normalized) Sentence field.
    return markup.normalize_spaces("".join(out))


def locate(term: str, reading: str, plain: str, acc) -> tuple[str, str, str] | None:
    """Return (before, inside, after) or None if the term can't be located."""
    if not term or not plain:
        return None
    pos = plain.find(term)
    if pos != -1:
        return plain[:pos], term, plain[pos + len(term):]
    us = units(plain, acc)
    if "".join(u.surface for u in us) != plain:     # offsets wouldn't be exact
        return None
    tr = hira(reading.strip()) if reading else ""
    target = next((u for u in us if u.lemma == term), None)  # lemma match
    if target is None and tr:                       # reading match (orthographic)
        target = next(
            (u for u in us if u.is_content
             and (hira(u.reading) == tr or hira(u.lemma_reading) == tr)),
            None,
        )
    if target is None:
        return None
    o = 0
    for u in us:
        if u is target:
            return plain[:o], u.surface, plain[o + len(u.surface):]
        o += len(u.surface)
    return None


def run(col, *, apply: bool = False, acc=None, overwrite=False) -> dict:
    """Fill cloze (Before/Inside/After) from Sentence. Returns {'filled', 'skipped'}.

    By default notes that already have cloze are left alone. overwrite=True
    re-splits the Sentence and rewrites existing cloze too, but only where the
    new split differs (no churn) and only when the term can still be located (an
    existing split is never cleared). `acc` (AccentIndex) is loaded lazily.
    """
    m = col.models.by_name(config.NOTETYPE)
    if m is None:
        return {"filled": 0, "skipped": 0}
    if acc is None:
        acc = AccentIndex.load()
    fld = {f["name"]: i for i, f in enumerate(m["flds"])}
    iT, iS, iR = fld[config.TERM], fld[config.SENTENCE], fld[config.READING]
    iB, iI, iA = fld[config.CLOZE_BEFORE], fld[config.CLOZE_INSIDE], fld[config.CLOZE_AFTER]

    filled, skipped, batch = 0, 0, []
    for nid in col.find_notes(notetype_search()):
        note = col.get_note(nid)
        f = note.fields
        has_cloze = any(f[k].strip() for k in (iB, iI, iA))
        if has_cloze and not overwrite:
            continue
        if not f[iS].strip():
            continue
        res = locate(f[iT].strip(), f[iR], strip_markup(f[iS]).strip(), acc)
        if res is None:
            if not has_cloze:               # keep an existing split if unlocatable now
                skipped += 1
            continue
        if (f[iB], f[iI], f[iA]) == res:     # already the correct split
            continue
        filled += 1
        if apply:
            f[iB], f[iI], f[iA] = res
            batch.append(note)
    if apply:
        for note in batch:
            col.update_note(note)
    return {"filled": filled, "skipped": skipped}
