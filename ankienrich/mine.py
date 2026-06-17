"""Single-word mining: create a fully-enriched Kinomoto card from one word and
sync it. The conversation flow lives here as pure helpers + a Session dataclass;
bot.py is the thin Telegram glue."""
from __future__ import annotations

from collections import Counter

from . import collection, config
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
