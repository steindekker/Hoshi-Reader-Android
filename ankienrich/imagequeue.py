"""On-demand image queue: flag newly-added imageless cards, then walk them one at a
time in the Telegram bot to fill the Image field. All queue policy lives here; the
bot only drives the conversation. No telebot imports."""
from __future__ import annotations

import re as _re
from dataclasses import dataclass, field

from . import collection, config, markup, runlock, sync

_TAGS = _re.compile(r"<[^>]+>")


def _plain(html: str, limit: int = 60) -> str:
    """Tags stripped, whitespace collapsed, truncated — a glanceable context line."""
    text = " ".join(_TAGS.sub(" ", html or "").split())
    return text[:limit]


def _context(sentence: str, meaning: str, limit: int = 60) -> str:
    """Prefer the example Sentence (JRP-marked → surface, e.g. {[一|ひと]つ;2} → 一つ),
    else the Meaning (plain HTML). Without the JRP strip the Sentence hint is
    unreadable."""
    if sentence.strip():
        return _plain(markup.jrp_surface(sentence), limit)
    return _plain(meaning, limit)


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
    """Imageless cards that want an image, oldest first (nid == creation timestamp).

    Selects structurally rather than waiting for a tag: an imageless card is offered
    unless it's a user-skip (no-image) or part of the already-processed back-catalog.
    "Processed" means the defs pass has terminated on it — either with a definition
    (enriched) or without one (no-definition, e.g. proper nouns / slang); both are old
    cards, not fresh adds. A card added from another app today carries neither tag yet,
    so it shows up immediately — no nightly pass required. The needs-image flag is a
    durability backstop: it keeps a card in the queue after the nightly defs pass
    enriches it (and stamps the flag), so skipping a day is safe.
    Context is a short plain-text snippet from Sentence, falling back to Meaning."""
    flds = _fields(col)
    q = (f'{collection.notetype_search()} "{config.IMAGE}:" '
         f'-tag:{config.NO_IMAGE_TAG} '
         f'((-tag:{config.ENRICHED_TAG} -tag:{config.NO_DEF_TAG}) '
         f'OR tag:{config.NEEDS_IMAGE_TAG})')
    out = []
    for nid in sorted(col.find_notes(q)):
        note = col.get_note(nid)
        sentence = note[config.SENTENCE] if config.SENTENCE in flds else ""
        meaning = note[config.DEFINITION] if config.DEFINITION in flds else ""
        out.append(Candidate(
            nid=nid,
            term=note[config.TERM].strip() if config.TERM in flds else "",
            reading=note[config.READING].strip() if config.READING in flds else "",
            context=_context(sentence, meaning),
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


@dataclass
class QueueSession:
    cards: list[Candidate]
    index: int = 0
    imaged: int = 0
    skipped: int = 0
    candidates: list = field(default_factory=list)   # current card's image hits

    @property
    def done(self) -> bool:
        return self.index >= len(self.cards)

    @property
    def current(self) -> Candidate | None:
        return None if self.done else self.cards[self.index]

    def advance(self) -> None:
        """Move past the current card without counting it (auto-skip / gone)."""
        self.index += 1

    def record_image(self) -> None:
        self.imaged += 1
        self.index += 1

    def record_skip(self) -> None:
        self.skipped += 1
        self.index += 1


def queue_image_rows(cands) -> list[tuple[str, str]]:
    rows = [(str(i + 1), f"qpick:{i}") for i in range(len(cands))]
    rows.append(("⏭ スキップ", "qskip"))
    rows.append(("■ 終了", "qend"))
    return rows


def summary_text(q: QueueSession, *, ended: bool = False) -> str:
    """Tally line. `ended` distinguishes an explicit 終了 tap from running the
    queue to its natural end."""
    head = "終了しました" if ended else "完了"
    return f"{head}（imaged {q.imaged}・skipped {q.skipped}）"
