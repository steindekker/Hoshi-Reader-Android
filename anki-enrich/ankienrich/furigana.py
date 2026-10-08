"""Back-catalog fix for the Anki furigana-notation fields.

Legacy jidoujisho imports wrote `base[reading]` groups without the delimiter
space the `furigana:` template filter needs, so a preceding particle gets
swallowed into the ruby base (`難[なん]を逃[のが]れる` renders のが over を逃).
`markup.fix_furigana_spacing` repairs the spacing; this module plans/applies it
over the Furigana + SentenceFurigana fields. Idempotent — only touches cards
that change, so it self-heals any future broken import on the nightly run.
"""
from __future__ import annotations
from collections import defaultdict
from dataclasses import dataclass

from . import config, markup

_FIELDS = (config.TERM_FURIGANA, config.SENTENCE_FURIGANA)


@dataclass(frozen=True)
class Fix:
    note_id: int
    field: str
    old: str
    new: str


def build_plan(col, *, note_ids=None) -> list[Fix]:
    mid = col.models.id_for_name(config.NOTETYPE)
    if mid is None:
        return []
    model = col.models.get(mid)
    names = [f["name"] for f in model["flds"]]
    idx = {f: names.index(f) for f in _FIELDS if f in names}
    plan: list[Fix] = []
    nids = list(col.models.nids(model))
    if note_ids is not None:
        wanted = set(int(n) for n in note_ids)
        nids = [n for n in nids if n in wanted]
    for nid in nids:
        note = col.get_note(nid)
        for field, fi in idx.items():
            old = note.fields[fi]
            if "[" not in old:
                continue
            new = markup.fix_furigana_spacing(old)
            if new != old:
                plan.append(Fix(nid, field, old, new))
    return plan


def apply_plan(col, plan: list[Fix]) -> int:
    by_note: dict[int, list[Fix]] = defaultdict(list)
    for fix in plan:
        by_note[fix.note_id].append(fix)
    notes = []
    for nid, fixes in by_note.items():
        note = col.get_note(nid)
        keys = note.keys()
        for fix in fixes:
            note.fields[keys.index(fix.field)] = fix.new
        notes.append(note)
    if notes:
        col.update_notes(notes)
    return len(plan)
