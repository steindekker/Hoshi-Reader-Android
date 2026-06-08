"""Open the working-copy collection, notetype search, and verified backup.

The tool operates on its OWN collection file (paths.WORKING_COLLECTION), never
the Anki desktop profile."""
import os
import time

from anki.collection import Collection

from . import config, paths


def open_working() -> Collection:
    """Open (creating if absent) the tool's working-copy collection."""
    paths.ensure_var()
    return Collection(paths.WORKING_COLLECTION)


def notetype_search() -> str:
    """Anki search for all notes of the target note type."""
    return f'note:"{config.NOTETYPE}"'


def backup(col, backup_dir: str | None = None) -> str:
    """Force a full backup and verify a FRESH .colpkg landed. Returns the dir.

    create_backup(force=True) writes nothing when there are no pending changes
    and the dir may hold old backups, so require a file newer than this call."""
    backup_dir = backup_dir or os.path.join(paths.VAR_DIR, "backups")
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
            f"no fresh backup created in {backup_dir}; aborting before mutation")
    return backup_dir
