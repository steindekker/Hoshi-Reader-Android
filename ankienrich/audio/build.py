"""Build the self-contained term-audio store (var/audio.sqlite) from the
local-audio-yomichan add-on. BUILD-TIME ONLY — runs where the add-on is
installed; the resulting sqlite is what ships and what the runtime opens.

The add-on's `file` column already embeds the per-source subdir, so every source
resolves as <user_files>/<source>_files/<file>. We copy all `entries` rows (the
lookup index, incl. forvo's term-only rows and JMdict form backfill) and embed
each distinct physical clip's bytes once in `clips`."""
import os
import sqlite3
from pathlib import Path

from .. import config, paths

_DEFAULT_ADDONS = os.path.expanduser("~/.local/share/Anki2/addons21")


def locate_addon(addons_dir=None) -> str | None:
    """Return the add-on's user_files dir (containing entries.db), or None."""
    base = addons_dir or os.environ.get("ANKI_ADDONS_DIR", _DEFAULT_ADDONS)
    uf = os.path.join(base, config.LOCAL_AUDIO_ADDON_ID, "user_files")
    return uf if os.path.isfile(os.path.join(uf, "entries.db")) else None


def _schema(conn):
    conn.execute("CREATE TABLE entries (expression text, reading text, "
                 "source text, speaker text, file text)")
    conn.execute("CREATE TABLE clips (source text, file text, data blob, "
                 "PRIMARY KEY (source, file))")


def build(user_files_dir: str, db_path: str) -> dict:
    """Transcode the add-on corpus into db_path. Returns
    {'entries': n, 'clips': n, 'missing': n}. Clips whose file is absent on disk
    are indexed in `entries` but skipped in `clips` (counted as missing)."""
    paths.ensure_var()
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    if os.path.exists(db_path):
        os.remove(db_path)

    src = sqlite3.connect(f"file:{os.path.join(user_files_dir, 'entries.db')}?mode=ro", uri=True)
    dst = sqlite3.connect(db_path)
    try:
        _schema(dst)
        rows = src.execute(
            "SELECT expression, reading, source, speaker, file FROM entries").fetchall()
        dst.executemany("INSERT INTO entries VALUES (?,?,?,?,?)", rows)

        seen, clips, missing = set(), 0, 0
        batch = []
        for _expr, _read, source, _spk, file in rows:
            key = (source, file)
            if key in seen:
                continue
            seen.add(key)
            path = os.path.join(user_files_dir, f"{source}_files", file)
            if not os.path.isfile(path):
                missing += 1
                continue
            with open(path, "rb") as fh:
                batch.append((source, file, fh.read()))
            if len(batch) >= 500:
                dst.executemany("INSERT INTO clips VALUES (?,?,?)", batch)
                clips += len(batch)
                batch = []
        if batch:
            dst.executemany("INSERT INTO clips VALUES (?,?,?)", batch)
            clips += len(batch)

        dst.execute("CREATE INDEX entries_key ON entries (expression, reading, source)")
        dst.commit()
        return {"entries": len(rows), "clips": clips, "missing": missing}
    finally:
        src.close()
        dst.close()


def ensure(addons_dir=None, db_path=None) -> str:
    """'present' if audio.sqlite exists, 'built' after building, 'no-source' if
    neither the store nor the add-on is available."""
    db_path = db_path or paths.AUDIO_DB
    if os.path.exists(db_path):
        return "present"
    uf = locate_addon(addons_dir)
    if uf is None:
        return "no-source"
    build(uf, db_path)
    return "built"
