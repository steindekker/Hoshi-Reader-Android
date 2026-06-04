"""Anki-facing definition enrichment: index lookup + note rewriting."""
import os
import sqlite3
from . import config, convert, render_defs

_COLS = ("term", "reading", "dict_id", "kind", "sense_ord", "html")


def open_index(path=None):
    conn = sqlite3.connect(path or config.DEF_INDEX_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def lookup(conn, term):
    """Return (jj_rows, je_rows) as lists of plain dicts for one term."""
    rows = conn.execute(
        "SELECT term, reading, dict_id, kind, sense_ord, html FROM defs WHERE term=?",
        (term,)).fetchall()
    jj = [dict(zip(_COLS, r)) for r in rows if r["kind"] == "jj"]
    je = [dict(zip(_COLS, r)) for r in rows if r["kind"] == "je"]
    return jj, je


_STYLE_MARKER = "/* ---- AnkiAlign dict styles"


def ensure_fields(col):
    """Add Original Meaning + Definition Source to the Kinomoto notetype if missing."""
    m = col.models.by_name(config.TARGET_NOTETYPE)
    if m is None:
        raise RuntimeError("target notetype missing: " + config.TARGET_NOTETYPE)
    existing = {f["name"] for f in m["flds"]}
    changed = False
    for fname in (config.ORIGINAL_MEANING_FIELD, config.DEFINITION_SOURCE_FIELD):
        if fname not in existing:
            col.models.add_field(m, col.models.new_field(fname))
            changed = True
    if changed:
        col.models.update_dict(m)


def apply_dict_styles(col, styles_path=None):
    """Append the scoped dict stylesheet to the notetype CSS (marker-guarded)."""
    path = styles_path or config.DICT_STYLES_PATH
    if not os.path.exists(path):
        return
    css = open(path, encoding="utf-8").read()
    m = col.models.by_name(config.TARGET_NOTETYPE)
    base = m["css"].split(_STYLE_MARKER, 1)[0]
    m["css"] = base.rstrip() + f"\n\n{_STYLE_MARKER} (generated) ---- */\n" + css
    col.models.update_dict(m)


def _compute(conn, term, reading):
    """Return (new_meaning, source, kind) for one note. kind in enrich/english/nohit."""
    jj_rows, je_rows = lookup(conn, term)
    jj = render_defs.choose_jj(jj_rows, reading, config.JJ_CHAIN)
    je = render_defs.numbered_glosses(je_rows, reading, config.GLOSS_CAP)
    meaning = render_defs.assemble_meaning(jj, je, english_open=not config.ENGLISH_COLLAPSED_DEFAULT)
    source = render_defs.source_label(jj, bool(je), config.DICT_LABELS)
    kind = "enrich" if jj else ("english" if je else "nohit")
    return meaning, source, kind


def _in_scope_nids(col):
    return col.find_notes(convert._search(config.TARGET_NOTETYPE))


def build_plan(col, conn):
    counts = {"enrich": 0, "english": 0, "nohit": 0}
    nohit_nids, samples, errors = [], [], []
    fld = {f["name"] for f in col.models.by_name(config.TARGET_NOTETYPE)["flds"]}
    for nid in _in_scope_nids(col):
        note = col.get_note(nid)
        term = note["Term"].strip() if "Term" in fld else ""
        if not term:
            continue
        reading = note["Reading"].strip() if "Reading" in fld else ""
        try:
            meaning, source, kind = _compute(conn, term, reading)
        except Exception as e:
            errors.append((nid, repr(e)))
            continue
        counts[kind] += 1
        if kind == "nohit":
            nohit_nids.append(nid)
        elif len(samples) < config.SAMPLE_N:
            samples.append((term, note["Meaning"], meaning, source))
    return {"counts": counts, "nohit_nids": nohit_nids,
            "samples": samples, "errors": errors}


def enrich_notes(col, conn):
    """Rewrite Meaning for in-scope Kinomoto notes. Returns counts. Idempotent.

    Caller is responsible for the backup + schema acknowledgement (the preview
    dialog does this). ensure_fields/apply_dict_styles are called here so the
    function is self-contained for headless use.
    """
    ensure_fields(col)
    apply_dict_styles(col)
    fld = {f["name"] for f in col.models.by_name(config.TARGET_NOTETYPE)["flds"]}
    written = 0
    nohit_nids = []
    for nid in _in_scope_nids(col):
        note = col.get_note(nid)
        term = note["Term"].strip() if "Term" in fld else ""
        if not term:
            continue
        reading = note["Reading"].strip() if "Reading" in fld else ""
        meaning, source, kind = _compute(conn, term, reading)
        if kind == "nohit":
            nohit_nids.append(nid)
            continue
        # stash the true original exactly once
        if config.ENRICHED_TAG not in note.tags:
            note[config.ORIGINAL_MEANING_FIELD] = note["Meaning"]
        unchanged = (note["Meaning"] == meaning
                     and note[config.DEFINITION_SOURCE_FIELD] == source
                     and config.ENRICHED_TAG in note.tags)
        if unchanged:
            continue
        note["Meaning"] = meaning
        note[config.DEFINITION_SOURCE_FIELD] = source
        if config.ENRICHED_TAG not in note.tags:
            note.add_tag(config.ENRICHED_TAG)
        col.update_note(note)
        written += 1

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
            "nohit_tagged": 0 if dropped else len(nohit_nids)}
