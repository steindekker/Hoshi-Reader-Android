"""Kinomoto note create: notetype ensure, field map, dedupe, media, add."""
from __future__ import annotations

import os
from dataclasses import dataclass

from anki.collection import Collection
from anki.notes import Note

from . import config


@dataclass(frozen=True)
class NoteSpec:
    term: str
    reading: str | None = None
    furigana: str | None = None
    sentence: str | None = None
    deck: str = config.DEFAULT_DECK
    image_path: str | None = None


def planned_media_name(path: str) -> str:
    return os.path.basename(path)


def image_html(filename: str) -> str:
    return f'<img src="{filename}">'


def find_notetype(col: Collection):
    for nt in col.models.all():
        if nt["name"] == config.NOTETYPE:
            return nt
    return None


def ensure_notetype(col: Collection):
    """Return Kinomoto notetype; create a minimal one if missing (tests / fresh)."""
    nt = find_notetype(col)
    if nt is not None:
        existing = {f["name"] for f in nt["flds"]}
        if config.IMAGE not in existing:
            col.models.add_field(nt, col.models.new_field(config.IMAGE))
            col.models.save(nt)
        return nt
    mm = col.models
    nt = mm.new(config.NOTETYPE)
    field_names = [
        config.TERM,
        config.READING,
        config.TERM_FURIGANA,
        config.SENTENCE,
        config.SENTENCE_FURIGANA,
        config.TERM_AUDIO,
        config.DEFINITION,
        config.PITCH,
        config.CLOZE_BEFORE,
        config.CLOZE_INSIDE,
        config.CLOZE_AFTER,
        config.ORIGINAL_MEANING,
        config.DEFINITION_SOURCE,
        config.EXPANDED_MEANING,
        config.EXAMPLES,
        config.FREQUENCY,
        config.IMAGE,
    ]
    for name in field_names:
        mm.add_field(nt, mm.new_field(name))
    t = mm.new_template("Card 1")
    t["qfmt"] = "{{Term}}"
    t["afmt"] = "{{FrontSide}}<hr>{{Meaning}}"
    mm.add_template(nt, t)
    mm.add(nt)
    return nt


def field_map(spec: NoteSpec, *, image_fname: str | None = None) -> dict[str, str]:
    img_value = ""
    if image_fname:
        img_value = image_html(image_fname)
    elif spec.image_path:
        img_value = image_html(planned_media_name(spec.image_path))
    return {
        config.TERM: spec.term,
        config.READING: spec.reading or "",
        config.TERM_FURIGANA: spec.furigana or "",
        config.SENTENCE: spec.sentence or "",
        config.SENTENCE_FURIGANA: "",
        config.TERM_AUDIO: "",
        config.DEFINITION: "",
        config.PITCH: "",
        config.CLOZE_BEFORE: "",
        config.CLOZE_INSIDE: "",
        config.CLOZE_AFTER: "",
        config.ORIGINAL_MEANING: "",
        config.DEFINITION_SOURCE: "",
        config.EXPANDED_MEANING: "",
        config.EXAMPLES: "",
        config.FREQUENCY: "",
        config.IMAGE: img_value,
    }


def add_media(col: Collection, path: str) -> str:
    return col.media.add_file(path)


def _escape_search(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def find_duplicates(col: Collection, spec: NoteSpec) -> list[int]:
    nt = find_notetype(col)
    if nt is None:
        return []
    query = (
        f'note:{_escape_search(config.NOTETYPE)} '
        f'{config.TERM}:{_escape_search(spec.term)}'
    )
    if spec.reading:
        query += f" {config.READING}:{_escape_search(spec.reading)}"
    return list(col.find_notes(query))


def ensure_deck(col: Collection, name: str) -> int:
    return col.decks.id(name, create=True)


def add_note(col: Collection, spec: NoteSpec, *, image_fname: str | None = None) -> int:
    nt = ensure_notetype(col)
    note = Note(col, nt)
    values = field_map(spec, image_fname=image_fname)
    for name, value in values.items():
        if name in note:
            note[name] = value
    did = ensure_deck(col, spec.deck)
    col.add_note(note, did)
    return note.id
