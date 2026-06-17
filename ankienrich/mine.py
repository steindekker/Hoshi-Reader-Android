"""Single-word mining: create a fully-enriched Kinomoto card from one word and
sync it. The conversation flow lives here as pure helpers + a Session dataclass;
bot.py is the thin Telegram glue."""
from __future__ import annotations

import re as _re
import requests
from collections import Counter
from dataclasses import dataclass, field
from urllib.parse import urlparse

from . import collection, config, massif, images, sync, runlock
from .audio import enrich as audio_enrich


def target_deck(col) -> str:
    """Deck for new cards: the configured override, else the modal deck of
    existing NOTETYPE cards, else the current deck."""
    if config.MINE_DECK:
        return config.MINE_DECK
    counts = Counter()
    for nid in col.find_notes(collection.notetype_search()):
        for cid in col.card_ids_of_note(nid):
            counts[col.get_card(cid).did] += 1
    if not counts:
        return col.decks.current()["name"]
    return col.decks.name(counts.most_common(1)[0][0])


def existing_note(col, term: str, reading: str) -> int | None:
    """nid of an existing Kinomoto note with this exact term+reading, or None."""
    escaped = term.replace('"', '\\"')  # term is arbitrary Telegram text
    q = f'note:"{config.NOTETYPE}" "{config.TERM}:{escaped}"'
    for nid in col.find_notes(q):
        n = col.get_note(nid)
        if n[config.TERM].strip() == term and n[config.READING].strip() == reading:
            return nid
    return None


def create_card(col, fields, sentence_fields, *, deck: str, tags, image=None) -> int:
    """Build + add the note. Caller holds the runlock and has backed up.
    `image` is (bytes, ext) | None."""
    m = col.models.by_name(config.NOTETYPE)
    if m is None:
        raise RuntimeError("target notetype missing: " + config.NOTETYPE)
    n = col.new_note(m)
    n[config.TERM] = fields.term
    n[config.READING] = fields.reading
    n[config.TERM_FURIGANA] = fields.furigana
    n[config.PITCH] = fields.pitch_svg
    n[config.DEFINITION] = fields.meaning
    n[config.DEFINITION_SOURCE] = fields.definition_source
    n[config.EXAMPLES] = fields.examples
    if fields.audio_clip and fields.audio_pick:
        fname = col.media.write_data(
            audio_enrich.media_name(fields.audio_pick), fields.audio_clip)
        n[config.TERM_AUDIO] = f"[sound:{fname}]"
    if sentence_fields is not None:
        n[config.SENTENCE] = sentence_fields.sentence
        n[config.SENTENCE_FURIGANA] = sentence_fields.sentence_furigana
        n[config.CLOZE_BEFORE] = sentence_fields.cloze_before
        n[config.CLOZE_INSIDE] = sentence_fields.cloze_inside
        n[config.CLOZE_AFTER] = sentence_fields.cloze_after
    if image is not None:
        data, ext = image
        fname = col.media.write_data(f"mined_{fields.term}.{ext}", data)
        n[config.IMAGE] = f'<img src="{fname}">'
    for t in tags:
        n.add_tag(t)
    col.add_note(n, col.decks.id(deck))
    return n.id


def card_tags(fields) -> list[str]:
    """Provenance + idempotency tags so nightly passes treat the card as settled:
    MINED_TAG always; ENRICHED_TAG (Meaning is dict-authoritative); EXAMPLES_TAG
    when an examples block was filled, else NO_EXAMPLE_TAG."""
    tags = [config.MINED_TAG, config.ENRICHED_TAG]
    tags.append(config.EXAMPLES_TAG if fields.examples else config.NO_EXAMPLE_TAG)
    return tags


def _local_sentences(ex_conn, term: str, n: int) -> list[str]:
    """Fallback plain sentences from examples.sqlite (across readings)."""
    rows = ex_conn.execute(
        "SELECT DISTINCT plain FROM examples WHERE term=? AND plain<>'' LIMIT ?",
        (term, n)).fetchall()
    return [r[0] for r in rows]


def sentence_options(term: str, *, ex_conn, n: int = config.MINE_SENTENCE_N) -> list[str]:
    """Top-n candidate sentences: Massif first, local examples.sqlite on failure."""
    hits = massif.top_sentences(term, n=n)
    if hits:
        return hits
    return _local_sentences(ex_conn, term, n)


def prefetch_images(term: str) -> None:
    """Start the image search early (e.g. at preview) so the candidates are ready
    by the image step. Never raises — best effort."""
    try:
        images.prefetch(term)
    except Exception:
        pass


def image_candidates(term: str, n: int = config.MINE_IMAGE_N):
    """Up to n Bing image candidates for `term`. Never raises: a missing browser,
    Playwright import error, or any search failure yields []. Consumes a
    prefetch()ed search if one is in flight."""
    try:
        return images.search_images(term, limit=n)
    except Exception:
        return []


def _ext_from_url(url: str) -> str:
    parts = urlparse(url).path.rsplit(".", 1)
    ext = parts[1].lower() if len(parts) == 2 else ""
    return ext if ext in {"jpg", "jpeg", "png", "webp", "gif"} else "jpg"


_MAX_IMAGE_BYTES = 10 * 1024 * 1024  # cap a user-driven download


def download_image(url: str, *, timeout: int = config.IMAGE_DL_TIMEOUT):
    """Download the chosen image. Returns (bytes, ext) or None on any failure /
    non-image content / oversize body."""
    try:
        resp = requests.get(url, headers={"User-Agent": "Mozilla/5.0 (anki-enrich)"},
                            timeout=timeout, stream=True)
        resp.raise_for_status()
        if not resp.headers.get("Content-Type", "").startswith("image/"):
            return None
        data = b""
        for chunk in resp.iter_content(64 * 1024):
            data += chunk
            if len(data) > _MAX_IMAGE_BYTES:
                return None
        return data, _ext_from_url(url)
    except requests.RequestException:
        return None


def commit(fields, sentence_fields, *, deck: str | None = None, image=None) -> dict:
    """Pull → dup-check → backup → add → push, under the run lock. Returns a status
    dict. Raises runlock.Busy if a run holds the lock."""
    with runlock.acquire():
        col = collection.open_working()
        try:
            auth = sync.login(col)
            pull_action, auth = sync.pull(col, auth)
            if existing_note(col, fields.term, fields.reading) is not None:
                return {"status": "duplicate"}
            collection.backup(col)
            deck = deck or target_deck(col)
            nid = create_card(col, fields, sentence_fields, deck=deck,
                              tags=card_tags(fields), image=image)
            push_action, auth = sync.push(col, auth)
            return {"status": "ok", "nid": nid, "deck": deck,
                    "pull": pull_action, "push": push_action}
        finally:
            col.close()


# ---------------------------------------------------------------------------
# Pure UI helpers (no Telegram imports; testable standalone)
# ---------------------------------------------------------------------------

_TAGS = _re.compile(r"<[^>]+>")


def _plain(s: str, limit: int) -> str:
    return _TAGS.sub("", s).strip()[:limit]


def candidate_rows(cands) -> list[tuple[str, str]]:
    """(button label, callback_data) per candidate reading."""
    return [(f"{c.reading} — {c.gloss}", f"cand:{i}") for i, c in enumerate(cands)]


def sentence_rows(sentences) -> list[tuple[str, str]]:
    rows = [(f"{i+1}. {_plain(s, 50)}", f"sent:{i}") for i, s in enumerate(sentences)]
    rows.append(("⏭ Skip sentence", "sent:skip"))
    return rows


def image_rows(cands) -> list[tuple[str, str]]:
    rows = [(f"{i+1}", f"img:{i}") for i in range(len(cands))]
    rows.append(("⏭ Skip image", "img:skip"))
    return rows


def preview_text(fields) -> str:
    lines = [f"📖 {fields.term}（{fields.reading}）", "", _plain(fields.meaning, 300)]
    if fields.definition_source:
        lines.append(f"— {fields.definition_source}")
    lines.append("🔊 audio: " + ("yes" if fields.audio_clip else "—"))
    lines.append("📈 pitch: " + ("yes" if fields.pitch_svg else "—"))
    return "\n".join(lines)


def _cloze_preview(sf) -> str:
    return f"{sf.cloze_before}【{sf.cloze_inside}】{sf.cloze_after}"


def confirm_text(fields, sentence_fields, has_image: bool = False) -> str:
    parts = [f"Add 「{fields.term}」（{fields.reading}）?"]
    parts.append("例: " + _cloze_preview(sentence_fields) if sentence_fields is not None
                 else "(no sentence)")
    parts.append("🖼 image: " + ("yes" if has_image else "—"))
    return "\n".join(parts)


@dataclass
class Session:
    chat_id: int
    state: str = "idle"               # idle|candidates|preview|sentence|image|confirm
    term: str = ""
    candidates: list = field(default_factory=list)
    fields: object | None = None
    sentences: list = field(default_factory=list)
    sentence_fields: object | None = None
    image_candidates: list = field(default_factory=list)
    image: tuple | None = None        # (bytes, ext) once downloaded
