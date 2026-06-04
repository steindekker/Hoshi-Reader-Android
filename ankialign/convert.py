"""Conversion engine. Uses the anki package; no aqt (GUI) imports so it can be
tested headless."""
from . import config, mapping


def _search(notetype_name: str) -> str:
    excl = " ".join(f"-deck:*{sub}*" for sub in config.EXCLUDE_DECK_SUBSTRINGS)
    return f'note:"{notetype_name}" {excl}'.strip()


def select_notes(col) -> dict:
    """{notetype_name: [note_id, ...]} for in-scope source notes present in col.

    Note: a note with any card in an excluded deck is excluded (search semantics).
    Fine for these single-card vocab notes.
    """
    out = {}
    for name in config.SOURCE_NOTETYPES:
        if col.models.by_name(name) is None:
            continue
        nids = list(col.find_notes(_search(name)))
        if nids:
            out[name] = nids
    return out


def _field_names(col, notetype_name) -> list:
    return [f["name"] for f in col.models.by_name(notetype_name)["flds"]]


def build_plan(col) -> dict:
    """Read-only: runs every mapper dry, collecting counts, samples, and errors."""
    selected = select_notes(col)
    plan = {"sources": {}, "errors": []}
    for name, nids in selected.items():
        mapper = mapping.MAPPERS[name]
        names = _field_names(col, name)
        samples = []
        for i, nid in enumerate(nids):
            src = dict(zip(names, col.get_note(nid).fields))
            try:
                kino = mapper(src)
            except Exception as e:  # surfaced in preview before any mutation
                plan["errors"].append((nid, repr(e)))
                continue
            if i < config.SAMPLE_N:
                samples.append((src, kino))
        plan["sources"][name] = {"count": len(nids), "samples": samples}
    return plan
