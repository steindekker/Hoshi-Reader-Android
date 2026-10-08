"""Open the working-copy collection, notetype search, and verified backup.

The tool operates on its OWN collection file (paths.WORKING_COLLECTION), never
the Anki desktop profile."""
import time
from pathlib import Path

from anki.collection import Collection

from . import config, paths


def open_working(path: str | None = None) -> Collection:
    """Open (creating if absent) the tool's working-copy collection."""
    paths.ensure_var()
    return Collection(path or paths.WORKING_COLLECTION)


def notetype_search(note_ids=None) -> str:
    """Anki search for target note type notes, optionally restricted to ids."""
    q = f'note:"{config.NOTETYPE}"'
    if note_ids is None:
        return q
    ids = [int(n) for n in note_ids]
    if not ids:
        # Match nothing (nid 0 is never allocated).
        return f"{q} nid:0"
    return q + " (" + " OR ".join(f"nid:{n}" for n in ids) + ")"


def note_ids_in_scope(col, note_ids=None) -> list:
    """Note ids of the target notetype, optionally intersected with note_ids."""
    return list(col.find_notes(notetype_search(note_ids)))


def backup(col, backup_dir: str | None = None) -> str:
    """Force a full backup and verify a FRESH .colpkg landed. Returns the dir.

    create_backup(force=True) writes nothing when there are no pending changes
    and the dir may hold old backups, so require a file newer than this call."""
    paths.ensure_var()
    dir_path = Path(backup_dir) if backup_dir else Path(paths.VAR_DIR, "backups")
    dir_path.mkdir(parents=True, exist_ok=True)
    before = time.time()
    col.create_backup(backup_folder=str(dir_path), force=True, wait_for_completion=True)
    fresh = [
        f for f in dir_path.glob("*.colpkg")
        if f.stat().st_mtime >= before - 1
    ]
    if not fresh:
        raise RuntimeError(
            f"no fresh backup created in {dir_path}; aborting before mutation")
    return str(dir_path)
