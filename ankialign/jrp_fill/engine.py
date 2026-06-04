"""Plan + apply JRP sentence fills over an opened Anki collection."""
from __future__ import annotations
from dataclasses import dataclass
from collections.abc import Callable
from .splice import splice
from ankialign import markup


def _norm(s: str) -> str:
    return s.replace("<br>", " ").replace("<br/>", " ").replace("　", " ")


@dataclass(frozen=True)
class Fill:
    note_id: int
    old_value: str
    new_value: str


def build_plan(col, note_type: str, field: str,
               generate: Callable[[str], str]) -> list[Fill]:
    """Generate the diff. `generate(plain_text) -> markup` is injected so this
    function stays testable without Sudachi."""
    mid = col.models.id_for_name(note_type)
    if mid is None:
        return []
    model = col.models.get(mid)
    names = [f["name"] for f in model["flds"]]
    if field not in names:
        raise ValueError(f"field {field!r} not found in note type {note_type!r}")
    fi = names.index(field)
    plan: list[Fill] = []
    for nid in col.models.nids(model):
        note = col.get_note(nid)
        old = note.fields[fi]
        if not old.strip():
            continue
        new = splice(old, generate)
        if new == old:
            continue
        # round-trip guard: visible surface must be unchanged
        if _norm(markup.jrp_surface(new)) != _norm(markup.jrp_surface(old)):
            continue
        plan.append(Fill(nid, old, new))
    return plan


def apply_plan(col, plan: list[Fill], field: str = "Sentence") -> int:
    notes = []
    for fill in plan:
        note = col.get_note(fill.note_id)
        fi = note.keys().index(field)
        note.fields[fi] = fill.new_value
        notes.append(note)
    if notes:
        col.update_notes(notes)
    return len(notes)
