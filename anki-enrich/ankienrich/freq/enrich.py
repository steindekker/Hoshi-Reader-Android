"""Anki-facing frequency enrichment: fill the Frequency field with the JPDB rank.

The rank is a pure function of (term, reading) — there is no hand-edited content
to protect — so the pass simply asserts the current rank on every in-scope note,
writing only when the stored value differs (idempotent, no sync churn). Hence no
tags and no overwrite flag: a word's rank changes only when the JPDB dict is
rebuilt, and the next run picks it up. Words with no rank are left empty.
"""
from ankienrich import config
from ankienrich.collection import notetype_search
from ankienrich.freq import index


def ensure_field(col) -> None:
    """Add the Frequency field to the Kinomoto notetype if missing."""
    m = col.models.by_name(config.NOTETYPE)
    if m is None:
        raise RuntimeError("target notetype missing: " + config.NOTETYPE)
    if config.FREQUENCY not in {f["name"] for f in m["flds"]}:
        col.models.add_field(m, col.models.new_field(config.FREQUENCY))
        col.models.update_dict(m)


def _term_reading(note, fld):
    term = note[config.TERM].strip() if config.TERM in fld else ""
    reading = note[config.READING].strip() if config.READING in fld else ""
    return term, reading


def _rank(conn, term, reading) -> str:
    """str(rank) for (term, reading), or '' when the word has no JPDB rank."""
    hit = index.lookup(conn, term, reading)
    return str(hit[0]) if hit else ""


def build_plan(col, conn, *, note_ids=None):
    """Dry-run: how many in-scope notes would change, plus a sample. `none` counts
    notes whose word has no rank (left as-is)."""
    to_write, none, samples = 0, 0, []
    fld = {f["name"] for f in col.models.by_name(config.NOTETYPE)["flds"]}
    for nid in col.find_notes(notetype_search(note_ids)):
        note = col.get_note(nid)
        term, reading = _term_reading(note, fld)
        if not term:
            continue
        rank = _rank(conn, term, reading)
        if not rank:
            none += 1
            continue
        cur = note[config.FREQUENCY] if config.FREQUENCY in fld else ""
        if cur != rank:
            to_write += 1
            if len(samples) < config.SAMPLE_N:
                samples.append((term, rank))
    return {"to_write": to_write, "none": none, "samples": samples}


def enrich_notes(col, conn, *, note_ids=None):
    """Assert the Frequency rank on every in-scope note, writing only on change.
    Returns counts; `none` = in-scope notes whose word has no rank."""
    ensure_field(col)
    fld = {f["name"] for f in col.models.by_name(config.NOTETYPE)["flds"]}
    written, none = 0, 0
    for nid in col.find_notes(notetype_search(note_ids)):
        note = col.get_note(nid)
        term, reading = _term_reading(note, fld)
        if not term:
            continue
        rank = _rank(conn, term, reading)
        if not rank:
            none += 1
            continue
        if note[config.FREQUENCY] == rank:
            continue
        note[config.FREQUENCY] = rank
        col.update_note(note)
        written += 1
    return {"written": written, "none": none}
