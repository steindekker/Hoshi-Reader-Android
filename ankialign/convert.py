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


def run_conversion(col, backup_dir: str) -> dict:
    """Backup, then convert all in-scope source notes to the target note type,
    preserving card history. Returns {'converted': {name: n}}.

    Each source notetype's change+repopulate is merged into one undo entry.
    col.models.change() always creates a new undo step (it calls mod_schema
    internally), so we anchor on the step it creates and merge subsequent
    update_note calls into it.
    """
    target = col.models.by_name(config.TARGET_NOTETYPE)
    if target is None:
        raise RuntimeError("target notetype missing: " + config.TARGET_NOTETYPE)

    selected = select_notes(col)
    if not any(selected.values()):
        raise RuntimeError("no in-scope notes to convert")

    import os
    os.makedirs(backup_dir, exist_ok=True)
    col.create_backup(backup_folder=backup_dir, force=True, wait_for_completion=True)

    converted = {}
    target_field_names = {f["name"] for f in target["flds"]}

    for name, nids in selected.items():
        model = col.models.by_name(name)
        names = _field_names(col, name)
        # snapshot BEFORE change(), which discards source fields
        snapshot = {nid: dict(zip(names, col.get_note(nid).fields)) for nid in nids}

        # switch note type + map every template onto the target's first template
        fmap = {i: None for i in range(len(names))}  # discard; repopulate below
        cmap = {t["ord"]: 0 for t in model["tmpls"]}
        col.models.change(model, list(nids), target, fmap, cmap)
        # anchor = the undo step just created by models.change
        anchor = col.undo_status().last_step

        mapper = mapping.MAPPERS[name]
        for nid in nids:
            note = col.get_note(nid)
            for fname, val in mapper(snapshot[nid]).items():
                if fname in target_field_names:
                    note[fname] = val
            col.update_note(note)

        # merge all update_note ops into the change_notetype undo entry
        col.merge_undo_entries(anchor)
        converted[name] = len(nids)

    return {"converted": converted}
