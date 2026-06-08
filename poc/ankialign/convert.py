"""Conversion engine. Uses the anki package; no aqt (GUI) imports so it can be
tested headless."""
import os
import time
from . import config, mapping, markup


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


def backup_collection(col, backup_dir: str) -> None:
    """Force a full backup and verify a FRESH .colpkg landed on disk.

    create_backup(force=True) returns False and writes nothing when there are no
    pending changes; backup_dir (the user's real backup folder) usually already
    holds old backups, so an existence check isn't enough — require a file newer
    than this call.
    """
    os.makedirs(backup_dir, exist_ok=True)
    before = time.time()
    col.create_backup(backup_folder=backup_dir, force=True, wait_for_completion=True)
    fresh = [
        f for f in os.listdir(backup_dir)
        if f.endswith(".colpkg")
        and os.path.getmtime(os.path.join(backup_dir, f)) >= before - 1
    ]
    if not fresh:
        raise RuntimeError(
            f"no fresh backup was created in {backup_dir}; aborting before mutation")


def run_conversion(col, backup_dir: str, make_backup: bool = True) -> dict:
    """Backup, then convert all in-scope source notes to the target note type,
    preserving card review history. Returns {'converted': {name: n}}.

    Rollback is the forced backup, NOT Ctrl+Z: col.models.change() is a schema
    op that clears Anki's undo queue, so the run is not cleanly undoable. We do
    no undo bookkeeping for that reason. Card scheduling history is preserved
    (change() operates at the note/field level, not on the cards table).
    """
    target = col.models.by_name(config.TARGET_NOTETYPE)
    if target is None:
        raise RuntimeError("target notetype missing: " + config.TARGET_NOTETYPE)

    selected = select_notes(col)
    if not any(selected.values()):
        raise RuntimeError("no in-scope notes to convert")

    if make_backup:
        backup_collection(col, backup_dir)

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

        # No undo bookkeeping: models.change is a schema op that clears Anki's
        # undo queue, so there's nothing to merge into. Rollback is the forced
        # backup, not Ctrl+Z (see backup_collection / preview messaging).
        mapper = mapping.MAPPERS[name]
        for nid in nids:
            try:
                note = col.get_note(nid)
                for fname, val in mapper(snapshot[nid]).items():
                    if fname in target_field_names:
                        note[fname] = val
                col.update_note(note)
            except Exception as e:
                raise RuntimeError(
                    f"failed repopulating note {nid} ({name}): {e!r}") from e

        converted[name] = len(nids)

    return {"converted": converted}


_MEDIA_FIELDS = {
    "Term Audio": markup.as_sound,
    "Sentence Audio": markup.as_sound,
    "Image": markup.as_image,
}


def normalize_media(col) -> int:
    """Wrap bare audio/image filenames on ALL target (Kinomoto) notes so Anki
    renders them. Idempotent — only notes that actually change are written
    (so already-correct notes aren't touched / re-synced). Returns count fixed.

    Fixes notes converted before media normalization existed (jidoujisho Yuuna
    stored bare filenames)."""
    target = col.models.by_name(config.TARGET_NOTETYPE)
    if target is None:
        return 0
    field_names = {f["name"] for f in target["flds"]}
    fixed = 0
    for nid in col.find_notes(f'note:"{config.TARGET_NOTETYPE}"'):
        note = col.get_note(nid)
        changed = False
        for fname, fn in _MEDIA_FIELDS.items():
            if fname in field_names:
                new = fn(note[fname])
                if new != note[fname]:
                    note[fname] = new
                    changed = True
        if changed:
            col.update_note(note)
            fixed += 1
    return fixed
