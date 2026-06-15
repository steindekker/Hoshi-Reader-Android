"""Plan + apply JRP sentence fills over an opened Anki collection."""
from __future__ import annotations
from dataclasses import dataclass
from collections.abc import Callable
from .splice import splice
from ankienrich import markup


def _norm(s: str) -> str:
    return s.replace("<br>", " ").replace("<br/>", " ").replace("　", " ")


@dataclass(frozen=True)
class Fill:
    note_id: int
    old_value: str
    new_value: str


def build_plan(col, note_type: str, field: str,
               generate: Callable[[str], str], regenerate: bool = False) -> list[Fill]:
    """Generate the diff. `generate(plain_text) -> markup` is injected so this
    function stays testable without Sudachi.

    Default (splice) preserves any existing `{..}`/`[..]` markup and only fills
    plain runs. `regenerate=True` instead strips each field back to its plain
    surface and rebuilds it wholesale — replacing previously-accepted markup so the
    whole deck uses the current generator consistently. The round-trip guard
    below still protects the visible text in both modes."""
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
        if regenerate:
            surface = markup.jrp_surface(old) if ("{" in old or "[" in old) else old
            new = generate(surface)
        else:
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


def apply_to_fixpoint(col, note_type: str, field: str,
                      generate: Callable[[str], str], *, regenerate: bool = False,
                      max_passes: int = 6) -> int:
    """Plan + apply repeatedly until no note changes (or max_passes).

    A single splice pass isn't a fixpoint: `generate` can emit a bare kanji that
    splice re-fills next pass, so one apply leaves multi-pass cases behind. Loop
    until stable. A regenerate pass is its OWN fixpoint (the generator is
    idempotent on its own output) and must NOT be followed by splice passes:
    splice would treat a token the rebuild intentionally left bare (a depitched
    suffix) as an empty gap and re-pitch it, reverting the overwrite. Returns
    total notes written."""
    total = 0
    for _ in range(max_passes):
        plan = build_plan(col, note_type, field, generate, regenerate=regenerate)
        if not plan:
            break
        total += apply_plan(col, plan, field)
        if regenerate:
            break
    return total
