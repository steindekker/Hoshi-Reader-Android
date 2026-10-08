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

# Canonicalization for cloze<->Sentence content comparison: keep only the visible
# text characters, discarding the encoding that legitimately differs between the two
# fields — <ruby> furigana, other HTML, subtitle labels, and whitespace (incl U+3000).
_RUBY_HTML = re.compile(r"<ruby>(.*?)<rt>.*?</rt></ruby>", re.S)
_HTML_TAG = re.compile(r"<[^>]+>")
_WS = re.compile(r"[\s　]+")


def _canon(s: str) -> str:
    s = _RUBY_HTML.sub(r"\1", s)
    s = _HTML_TAG.sub("", s)
    s = markup.strip_subtitle_labels(s)
    return _WS.sub("", s)


def resync(before: str, inside: str, after: str, plain: str) -> tuple[str, str, str] | None:
    """Re-project an existing cloze split onto the (normalized) plain Sentence.

    Returns a fresh (before, inside, after) whose concatenation is exactly `plain`
    and whose Inside covers the same term content as the input — but re-encoded to
    match the Sentence (speaker label dropped, U+3000 → space, ruby → plain). Only
    valid when the cloze and Sentence carry the SAME visible content; when they
    genuinely differ (a different mined line) returns None so the caller can flag it.
    """
    cb, ci = _canon(before), _canon(inside)
    if not ci:                                          # no term content to anchor on
        return None
    pc = _canon(plain)
    if cb + ci + _canon(after) != pc:                   # content differs -> can't reproject
        return None
    # map each canonical char back to its source offset in `plain` (canon only ever
    # deletes chars or keeps a ruby base, so a two-pointer alignment is exact).
    idx, j = [], 0
    for i, ch in enumerate(plain):
        if j < len(pc) and ch == pc[j]:
            idx.append(i)
            j += 1
    if j != len(pc) or len(cb) + len(ci) > len(idx):
        return None
    start = idx[len(cb)] if len(cb) < len(idx) else len(plain)
    last = idx[len(cb) + len(ci) - 1]
    return plain[:start], plain[start:last + 1], plain[last + 1:]


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


def _set_tag(note, tag: str, present: bool) -> bool:
    """Add/remove `tag`; return True iff the note's tags changed."""
    has = note.has_tag(tag)
    if present and not has:
        note.add_tag(tag)
        return True
    if not present and has:
        note.remove_tag(tag)
        return True
    return False


def run(col, *, apply: bool = False, acc=None, overwrite=False, note_ids=None) -> dict:
    """Fill/repair cloze (Before/Inside/After) from Sentence. Returns counts
    {'filled', 'resynced', 'skipped', 'mismatched'}.

    - Empty cloze: split the Sentence around the Term (see `locate`).
    - Existing cloze that reconstructs the SAME text as the Sentence but is encoded
      differently (a stripped speaker label, a full-width space, ruby furigana) is
      re-projected onto the plain Sentence — `resynced`. Runs in every mode, since
      the Sentence-normalization steps upstream routinely desync it.
    - Existing cloze whose text genuinely differs from the Sentence (a different
      subtitle line captured at mining time) can't be repaired here — it's tagged
      `cloze-mismatch` for review (`mismatched`); the tag self-heals once consistent.
    - overwrite=True additionally re-splits a still-consistent-but-differently-split
      cloze; an existing split is never cleared. `acc` (AccentIndex) is loaded lazily.
    """
    m = col.models.by_name(config.NOTETYPE)
    if m is None:
        return {"filled": 0, "resynced": 0, "skipped": 0, "mismatched": 0}
    if acc is None:
        acc = AccentIndex.load()
    fld = {f["name"]: i for i, f in enumerate(m["flds"])}
    iT, iS, iR = fld[config.TERM], fld[config.SENTENCE], fld[config.READING]
    iB, iI, iA = fld[config.CLOZE_BEFORE], fld[config.CLOZE_INSIDE], fld[config.CLOZE_AFTER]

    filled = resynced = skipped = mismatched = 0
    batch = []
    for nid in col.find_notes(notetype_search(note_ids)):
        note = col.get_note(nid)
        f = note.fields
        if not f[iS].strip():
            continue
        plain = strip_markup(f[iS]).strip()
        cur = (f[iB], f[iI], f[iA])
        has_cloze = any(part.strip() for part in cur)

        if has_cloze:
            projected = resync(*cur, plain)
            if projected is not None:            # same content: repair encoding, clear flag
                changed = apply and _set_tag(note, config.CLOZE_MISMATCH_TAG, False)
                if projected != cur:
                    resynced += 1
                    if apply:
                        f[iB], f[iI], f[iA] = projected
                    changed = True
                if apply and changed:
                    batch.append(note)
                continue
            if overwrite:                        # try a clean re-split from the Sentence
                res = locate(f[iT].strip(), f[iR], plain, acc)
                if res is not None and res != cur:
                    filled += 1
                    if apply:
                        f[iB], f[iI], f[iA] = res
                        _set_tag(note, config.CLOZE_MISMATCH_TAG, False)
                        batch.append(note)
                    continue
            mismatched += 1                      # genuinely different line: flag for review
            if apply and _set_tag(note, config.CLOZE_MISMATCH_TAG, True):
                batch.append(note)
            continue

        res = locate(f[iT].strip(), f[iR], plain, acc)
        if res is None:
            skipped += 1
            continue
        filled += 1
        if apply:
            f[iB], f[iI], f[iA] = res
            batch.append(note)

    if apply:
        for note in batch:
            col.update_note(note)
    return {"filled": filled, "resynced": resynced, "skipped": skipped, "mismatched": mismatched}
