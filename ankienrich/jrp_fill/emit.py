"""Render Unit lists into jidoujisho/JRP default-syntax markup."""
from __future__ import annotations
from .unit import Unit
from .align import furigana_segments

def _furi(surface: str, reading: str) -> str:
    segs = furigana_segments(surface, reading)
    return "".join(f"[{b}|{r}]" if r else b for b, r in segs)

def emit_unit(u: Unit) -> str:
    if not u.is_content:
        return u.surface
    # reading == surface -> nothing to annotate (e.g. 何 rendered bare, no furigana)
    body = u.surface if u.reading == u.surface else _furi(u.surface, u.reading)
    if u.accents:
        flag = ("!" if u.ambiguous else "") + ("Y" if u.is_yougen else "")
        acc = ",".join(str(a) for a in u.accents)
        # conjugated yougen: by default the accent is the dict-form downstep, so
        # give the renderer the dict reading as JRP base (`|わかる`) — otherwise it
        # would place the downstep on the inflected reading. When the conjugated
        # downstep has been COMPUTED, it already sits on `reading`, so emit no base.
        base = ("|" + u.lemma_reading if u.is_yougen and not u.conj_computed
                and u.reading != u.lemma_reading else "")
        return "{" + body + ";" + flag + acc + base + "}"
    return body

def emit_sentence(units: list[Unit]) -> str:
    return "".join(emit_unit(u) for u in units)
