"""Anki-facing definition enrichment: index lookup + note rewriting."""
from pathlib import Path

from ankienrich import config, paths
from ankienrich.defs import index, render
from ankienrich.collection import notetype_search


def ensure_fields(col) -> None:
    """Add Original Meaning + Definition Source to the Kinomoto notetype if missing."""
    m = col.models.by_name(config.NOTETYPE)
    if m is None:
        raise RuntimeError("target notetype missing: " + config.NOTETYPE)
    existing = {f["name"] for f in m["flds"]}
    changed = False
    for fname in (config.ORIGINAL_MEANING, config.DEFINITION_SOURCE):
        if fname not in existing:
            col.models.add_field(m, col.models.new_field(fname))
            changed = True
    if changed:
        col.models.update_dict(m)


def apply_dict_styles(col, styles_path=None) -> None:
    """Append the scoped dict stylesheet to the notetype CSS (marker-guarded)."""
    path = Path(styles_path or paths.DICT_STYLES)
    if not path.exists():
        return
    css = path.read_text(encoding="utf-8")
    m = col.models.by_name(config.NOTETYPE)
    base = m["css"].split(config.DICT_STYLES_MARKER, 1)[0]
    m["css"] = base.rstrip() + f"\n\n{config.DICT_STYLES_MARKER}\n" + css
    col.models.update_dict(m)


def _compute(conn, term, reading):
    """Return (new_meaning, source, kind) for one note. kind in enrich/english/nohit."""
    jj_rows, je_rows = index.lookup(conn, term)
    jj = render.choose_jj(jj_rows, reading, config.JJ_CHAIN)
    je = render.numbered_glosses(je_rows, reading, config.GLOSS_CAP)
    meaning = render.assemble_meaning(jj, je)
    source = render.source_label(jj, bool(je), config.DICT_LABELS)
    kind = "enrich" if jj else ("english" if je else "nohit")
    return meaning, source, kind


def _in_scope_nids(col):
    return col.find_notes(notetype_search())


def _will_write(note, meaning, source, fld, overwrite) -> bool:
    """Would enrich_notes write this (non-nohit) note under `overwrite`?

    A never-enriched note is always written. An already-enriched note is left
    alone (preserving manual edits) unless overwrite re-asserts a differing def."""
    if config.ENRICHED_TAG not in note.tags:
        return True
    if not overwrite:
        return False
    cur_src = note[config.DEFINITION_SOURCE] if config.DEFINITION_SOURCE in fld else ""
    return not (note[config.DEFINITION] == meaning and cur_src == source)


def build_plan(col, conn, *, overwrite=False):
    counts = {"enrich": 0, "english": 0, "nohit": 0}
    nohit_nids, samples, errors, terms = [], [], [], []
    to_write = 0
    fld = {f["name"] for f in col.models.by_name(config.NOTETYPE)["flds"]}
    for nid in _in_scope_nids(col):
        note = col.get_note(nid)
        term = note[config.TERM].strip() if config.TERM in fld else ""
        if not term:
            continue
        reading = note[config.READING].strip() if config.READING in fld else ""
        try:
            meaning, source, kind = _compute(conn, term, reading)
        except Exception as e:
            errors.append((nid, repr(e)))
            continue
        counts[kind] += 1
        if kind == "nohit":
            nohit_nids.append(nid)
            continue
        if _will_write(note, meaning, source, fld, overwrite):
            to_write += 1
            terms.append(term)
        if len(samples) < config.SAMPLE_N:
            samples.append((term, note[config.DEFINITION], meaning, source))
    return {"counts": counts, "to_write": to_write, "nohit_nids": nohit_nids,
            "samples": samples, "errors": errors, "terms": terms}


def enrich_notes(col, conn, styles_path=None, *, overwrite=False):
    """Rewrite Meaning for in-scope Kinomoto notes. Returns counts. Idempotent.

    By default only notes that have never been enriched are written, so manual
    edits to an already-enriched Meaning are preserved. Pass overwrite=True to
    re-assert the dictionary definition on every in-scope note (clobbering any
    later hand-edits). The original mined Meaning is archived once, on the first
    enrichment, regardless.

    Caller is responsible for the backup + schema acknowledgement (the preview
    dialog does this). ensure_fields / apply_dict_styles are called here so
    the function is self-contained for headless use.
    """
    ensure_fields(col)
    apply_dict_styles(col, styles_path)
    fld = {f["name"] for f in col.models.by_name(config.NOTETYPE)["flds"]}
    written = 0
    nohit_nids, terms = [], []
    for nid in _in_scope_nids(col):
        note = col.get_note(nid)
        term = note[config.TERM].strip() if config.TERM in fld else ""
        if not term:
            continue
        reading = note[config.READING].strip() if config.READING in fld else ""
        meaning, source, kind = _compute(conn, term, reading)
        if kind == "nohit":
            nohit_nids.append(nid)
            continue
        first_time = config.ENRICHED_TAG not in note.tags
        if not _will_write(note, meaning, source, fld, overwrite):
            continue                                  # already enriched, preserved
        if first_time:                                # stash the true original once
            note[config.ORIGINAL_MEANING] = note[config.DEFINITION]
        note[config.DEFINITION] = meaning
        note[config.DEFINITION_SOURCE] = source
        if config.EXPANDED_MEANING in fld:
            note[config.EXPANDED_MEANING] = ""
        if first_time:
            note.add_tag(config.ENRICHED_TAG)
        col.update_note(note)
        written += 1
        terms.append(term)

    dropped = 0
    if nohit_nids and len(nohit_nids) < config.NO_HIT_DROP_THRESHOLD:
        col.remove_notes(nohit_nids)
        dropped = len(nohit_nids)
    else:
        for nid in nohit_nids:
            note = col.get_note(nid)
            if config.NO_DEF_TAG not in note.tags:
                note.add_tag(config.NO_DEF_TAG)
                col.update_note(note)
    return {"written": written, "dropped": dropped,
            "nohit_tagged": 0 if dropped else len(nohit_nids), "terms": terms}
