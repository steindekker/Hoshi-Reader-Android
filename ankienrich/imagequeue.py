"""On-demand image queue: flag newly-added imageless cards, then walk them one at a
time in the Telegram bot to fill the Image field. All queue policy lives here; the
bot only drives the conversation. No telebot imports."""
from __future__ import annotations

import re as _re
from dataclasses import dataclass, field

from . import collection, config, runlock, sync

_TAGS = _re.compile(r"<[^>]+>")


def _plain(html: str, limit: int = 60) -> str:
    """Tags stripped, whitespace collapsed, truncated — a glanceable context line."""
    text = " ".join(_TAGS.sub(" ", html or "").split())
    return text[:limit]


@dataclass(frozen=True)
class Candidate:
    nid: int
    term: str
    reading: str
    context: str


def _fields(col) -> set[str]:
    return {f["name"] for f in col.models.by_name(config.NOTETYPE)["flds"]}


def flag_new(col, nids) -> int:
    """Tag each imageless, not-yet-processed note with NEEDS_IMAGE_TAG. A note is
    skipped if it already has an Image, or already carries needs-image/no-image.
    Returns the count newly flagged. Caller's pull/push envelope persists the tags."""
    flds = _fields(col)
    flagged = 0
    for nid in nids:
        note = col.get_note(nid)
        if config.IMAGE in flds and note[config.IMAGE].strip():
            continue
        if config.NEEDS_IMAGE_TAG in note.tags or config.NO_IMAGE_TAG in note.tags:
            continue
        note.add_tag(config.NEEDS_IMAGE_TAG)
        col.update_note(note)
        flagged += 1
    return flagged


def candidates(col) -> list[Candidate]:
    """Flagged notes, oldest first (nid == creation timestamp). Context is a short
    plain-text snippet from Sentence, falling back to Meaning."""
    flds = _fields(col)
    q = f'{collection.notetype_search()} tag:{config.NEEDS_IMAGE_TAG}'
    out = []
    for nid in sorted(col.find_notes(q)):
        note = col.get_note(nid)
        sentence = note[config.SENTENCE] if config.SENTENCE in flds else ""
        meaning = note[config.DEFINITION] if config.DEFINITION in flds else ""
        out.append(Candidate(
            nid=nid,
            term=note[config.TERM].strip() if config.TERM in flds else "",
            reading=note[config.READING].strip() if config.READING in flds else "",
            context=_plain(sentence or meaning),
        ))
    return out


def _load(col, nid):
    """The note for nid, or None if it vanished remotely between OPEN and the pick."""
    try:
        return col.get_note(nid)
    except Exception:
        return None


def open_queue() -> list[Candidate]:
    """The single OPEN pull, encapsulated so bot.py stays thin. runlock → open →
    pull → candidates → close. Raises runlock.Busy if a run holds the lock."""
    with runlock.acquire():
        col = collection.open_working()
        try:
            auth = sync.login(col)
            _, auth = sync.pull(col, auth)
            return candidates(col)
        finally:
            col.close()


def set_image(nid: int, image) -> dict:
    """Write Image + remove needs-image for one note, under the run lock.
    `image` is (bytes, ext). Returns a status dict; "gone" if the note is absent."""
    with runlock.acquire():
        col = collection.open_working()
        try:
            auth = sync.login(col)
            _, auth = sync.pull(col, auth)
            note = _load(col, nid)
            if note is None:
                return {"status": "gone"}
            data, ext = image
            fname = col.media.write_data(f"q_{nid}.{ext}", data)
            note[config.IMAGE] = f'<img src="{fname}">'
            if config.NEEDS_IMAGE_TAG in note.tags:
                note.remove_tag(config.NEEDS_IMAGE_TAG)
            col.update_note(note)
            _, auth = sync.push(col, auth)
            return {"status": "ok", "nid": nid, "file": fname}
        finally:
            col.close()


def skip(nid: int) -> dict:
    """Swap needs-image for no-image (permanent exclusion), under the run lock."""
    with runlock.acquire():
        col = collection.open_working()
        try:
            auth = sync.login(col)
            _, auth = sync.pull(col, auth)
            note = _load(col, nid)
            if note is None:
                return {"status": "gone"}
            if config.NEEDS_IMAGE_TAG in note.tags:
                note.remove_tag(config.NEEDS_IMAGE_TAG)
            note.add_tag(config.NO_IMAGE_TAG)
            col.update_note(note)
            _, auth = sync.push(col, auth)
            return {"status": "ok", "nid": nid}
        finally:
            col.close()
