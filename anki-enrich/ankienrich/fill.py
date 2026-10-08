"""Fill the word-level Kinomoto fields from the Hoshi pack: the same values, by the
same lookup (`pack.find`), the app writes at mining time. One route — sources ->
pack -> app and this pass — so a phone-mined card is already settled here, and a
rebuilt source reaches the whole collection with `--overwrite-fields`.

Per field, what a run without overwrite does:

  Meaning, Definition Source  first enrichment only (no ENRICHED_TAG): the mined
                              Meaning is stashed in Original Meaning once.
                              Hand edits to an enriched card are kept.
  Examples                    until the card carries EXAMPLES_TAG; a word with no
                              examples is tagged NO_EXAMPLE_TAG and rechecked.
  Pitch Accent, Frequency     always asserted: pure functions of the word, no hand
                              edits to protect. A non-graph Pitch the dicts can't
                              replace is cleared; our own graph is kept.

overwrite re-asserts Meaning and Examples wherever they differ. A term the pack
does not know is a no-hit: dropped when there are only a few (mining mistakes),
tagged NO_DEF_TAG otherwise."""
from __future__ import annotations

import sqlite3
from dataclasses import dataclass, field

from . import config, pack
from .collection import notetype_search
from .defs import enrich as defs_enrich


def ensure_fields(col) -> None:
    """Add the fields this pass writes to the Kinomoto notetype if missing."""
    m = col.models.by_name(config.NOTETYPE)
    if m is None:
        raise RuntimeError("target notetype missing: " + config.NOTETYPE)
    existing = {f["name"] for f in m["flds"]}
    missing = [f for f in (config.ORIGINAL_MEANING, config.DEFINITION_SOURCE,
                           config.EXAMPLES, config.FREQUENCY) if f not in existing]
    for name in missing:
        col.models.add_field(m, col.models.new_field(name))
    if missing:
        col.models.update_dict(m)


@dataclass
class Change:
    """What one note needs. Empty when the note is settled."""
    fields: dict[str, str] = field(default_factory=dict)
    add_tags: list[str] = field(default_factory=list)
    remove_tags: list[str] = field(default_factory=list)
    counts: list[str] = field(default_factory=list)

    def __bool__(self) -> bool:
        return bool(self.fields or self.add_tags or self.remove_tags)


def plan_note(note, values: dict[str, str] | None, *, overwrite: bool, fld: set[str]) -> Change:
    """The change for one note given its pack values (None = term unknown)."""
    ch = Change()
    values = values or {}
    tags = set(note.tags)

    def set_field(name: str, value: str) -> bool:
        if name in fld and note[name] != value:
            ch.fields[name] = value
            return True
        return False

    meaning = values.get(pack.DEFINITION)
    if meaning:
        first = config.ENRICHED_TAG not in tags
        if first or overwrite:
            changed = set_field(config.DEFINITION, meaning)
            changed |= set_field(config.DEFINITION_SOURCE, values.get(pack.DEFINITION_SOURCE, ""))
            if first:
                if config.ORIGINAL_MEANING in fld:
                    ch.fields[config.ORIGINAL_MEANING] = note[config.DEFINITION]
                set_field(config.EXPANDED_MEANING, "")
                ch.add_tags.append(config.ENRICHED_TAG)
            if changed or first:
                ch.counts.append("defs")

    examples = values.get(pack.EXAMPLES)
    pack_tags = values.get(pack.TAGS, "").split()
    if examples and (config.EXAMPLES_TAG not in tags or overwrite):
        if set_field(config.EXAMPLES, examples) or config.EXAMPLES_TAG not in tags:
            ch.counts.append("examples")
        if config.EXAMPLES_TAG not in tags:
            ch.add_tags.append(config.EXAMPLES_TAG)
        if config.NO_EXAMPLE_TAG in tags:
            ch.remove_tags.append(config.NO_EXAMPLE_TAG)
    elif (not examples and config.NO_EXAMPLE_TAG in pack_tags
          and not tags & {config.EXAMPLES_TAG, config.NO_EXAMPLE_TAG}):
        ch.add_tags.append(config.NO_EXAMPLE_TAG)
        ch.counts.append("no_example")

    graph = values.get(pack.PITCH)
    current = note[config.PITCH] if config.PITCH in fld else ""
    if graph:
        if set_field(config.PITCH, graph):
            ch.counts.append("pitch")
    elif current.strip() and "<svg" not in current:
        set_field(config.PITCH, "")
        ch.counts.append("pitch_cleared")

    rank = values.get(pack.FREQUENCY)
    if rank and set_field(config.FREQUENCY, rank):
        ch.counts.append("frequency")
    return ch


def _apply(col, note, ch: Change) -> None:
    for name, value in ch.fields.items():
        note[name] = value
    for tag in ch.add_tags:
        note.add_tag(tag)
    for tag in ch.remove_tags:
        note.remove_tag(tag)
    col.update_note(note)


_COUNTS = ("defs", "examples", "no_example", "pitch", "pitch_cleared", "frequency")


def run(col, conn: sqlite3.Connection, *, apply: bool, overwrite: bool = False,
        note_ids=None, styles_path=None) -> dict:
    """Fill every in-scope note from the pack. Dry run unless apply. Returns counts;
    `terms` lists the words whose definition was (or would be) written."""
    if apply:
        ensure_fields(col)
        defs_enrich.apply_dict_styles(col, styles_path)
    m = col.models.by_name(config.NOTETYPE)
    fld = {f["name"] for f in m["flds"]} if m else set()
    out = {k: 0 for k in _COUNTS}
    out.update(notes=0, nohit=0, dropped=0, terms=[])
    nohit_nids = []
    for nid in col.find_notes(notetype_search(note_ids)):
        note = col.get_note(nid)
        term = note[config.TERM].strip() if config.TERM in fld else ""
        if not term:
            continue
        reading = note[config.READING].strip() if config.READING in fld else ""
        values = pack.find(conn, term, reading)
        if values is None:
            nohit_nids.append(nid)
        ch = plan_note(note, values, overwrite=overwrite, fld=fld)
        if not ch:
            continue
        for key in ch.counts:
            out[key] += 1
        if "defs" in ch.counts:
            out["terms"].append(term)
        out["notes"] += 1
        if apply:
            _apply(col, note, ch)

    out["nohit"] = len(nohit_nids)
    if nohit_nids and len(nohit_nids) < config.NO_HIT_DROP_THRESHOLD:
        out["dropped"] = len(nohit_nids)
        if apply:
            col.remove_notes(nohit_nids)
    elif apply:
        for nid in nohit_nids:
            note = col.get_note(nid)
            if config.NO_DEF_TAG not in note.tags:
                note.add_tag(config.NO_DEF_TAG)
                col.update_note(note)
    return out
