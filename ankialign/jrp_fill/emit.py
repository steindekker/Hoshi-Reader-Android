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
    body = _furi(u.surface, u.reading)
    if u.accents:
        flag = ("!" if u.ambiguous else "") + ("Y" if u.is_yougen else "")
        acc = ",".join(str(a) for a in u.accents)
        return "{" + body + ";" + flag + acc + "}"
    return body

def emit_sentence(units: list[Unit]) -> str:
    return "".join(emit_unit(u) for u in units)
