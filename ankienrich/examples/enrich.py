"""Anki-facing example enrichment: index lookup + note rewriting. Idempotent.

Only ever fills the Examples field; never deletes notes. A first-time note is
written when examples exist (tagged EXAMPLES_TAG) or tagged NO_EXAMPLE_TAG when
none are found, so re-runs skip settled cards. overwrite re-asserts a differing
block on every in-scope note."""
from ankienrich import config
from ankienrich.collection import notetype_search
from ankienrich.examples import index, render


def ensure_field(col) -> None:
    """Add the Examples field to the Kinomoto notetype if missing."""
    m = col.models.by_name(config.NOTETYPE)
    if m is None:
        raise RuntimeError("target notetype missing: " + config.NOTETYPE)
    if config.EXAMPLES not in {f["name"] for f in m["flds"]}:
        col.models.add_field(m, col.models.new_field(config.EXAMPLES))
        col.models.update_dict(m)


def _compute(conn, term, reading) -> str:
    return render.render_block(index.select(conn, term, reading))


def _will_write(note, new_html, fld, overwrite) -> bool:
    if config.EXAMPLES_TAG not in note.tags:
        return True
    if not overwrite:
        return False
    cur = note[config.EXAMPLES] if config.EXAMPLES in fld else ""
    return cur != new_html


def _term_reading(note, fld):
    term = note[config.TERM].strip() if config.TERM in fld else ""
    reading = note[config.READING].strip() if config.READING in fld else ""
    return term, reading


def build_plan(col, conn, *, overwrite=False):
    counts = {"fill": 0, "none": 0}
    to_write, samples, errors = 0, [], []
    fld = {f["name"] for f in col.models.by_name(config.NOTETYPE)["flds"]}
    for nid in col.find_notes(notetype_search()):
        note = col.get_note(nid)
        term, reading = _term_reading(note, fld)
        if not term:
            continue
        try:
            new_html = _compute(conn, term, reading)
        except Exception as e:
            errors.append((nid, repr(e)))
            continue
        if not new_html:
            counts["none"] += 1
            continue
        counts["fill"] += 1
        if _will_write(note, new_html, fld, overwrite):
            to_write += 1
            if len(samples) < config.SAMPLE_N:
                samples.append((term, new_html))
    return {"counts": counts, "to_write": to_write, "samples": samples, "errors": errors}


def enrich_notes(col, conn, *, overwrite=False):
    """Fill Examples for in-scope Kinomoto notes. Returns counts."""
    ensure_field(col)
    fld = {f["name"] for f in col.models.by_name(config.NOTETYPE)["flds"]}
    written, none_tagged = 0, 0
    for nid in col.find_notes(notetype_search()):
        note = col.get_note(nid)
        term, reading = _term_reading(note, fld)
        if not term:
            continue
        new_html = _compute(conn, term, reading)
        if not new_html:
            if config.NO_EXAMPLE_TAG not in note.tags and config.EXAMPLES_TAG not in note.tags:
                note.add_tag(config.NO_EXAMPLE_TAG)
                col.update_note(note)
                none_tagged += 1
            continue
        if not _will_write(note, new_html, fld, overwrite):
            continue
        note[config.EXAMPLES] = new_html
        note.remove_tag(config.NO_EXAMPLE_TAG)
        if config.EXAMPLES_TAG not in note.tags:
            note.add_tag(config.EXAMPLES_TAG)
        col.update_note(note)
        written += 1
    return {"written": written, "none_tagged": none_tagged}
