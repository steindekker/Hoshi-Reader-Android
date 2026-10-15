"""Sentence model: what the Hoshi app needs to write JRP sentence markup offline
(`{ae-sentence}`, the app's port of `jrp_fill`). Published beside the pack and listed
in `pack.json` under "sentence", each file with its own version, size and SHA-256,
so a pack change never re-downloads the 360 MB dictionary and vice versa. Clients
that predate it ignore the key.

  "sentence": {
    "format": 1,                     the app skips a format it does not know
    "accent":     {"file", "version", "size", "sha256", "entries"},
    "dictionary": {"file", "version", "size", "sha256", "source"}
  }

ACCENT_FILE (SENTENCE FORMAT 1) — the accent index (`jrp_fill/accent.py`) as SQLite,
with every query precomputed by AccentIndex itself so the app answers exactly what
the server would, without building reverse maps in memory:

  meta(key TEXT PRIMARY KEY, value TEXT)    format, version (content hash), entries
  accent(lemma TEXT, reading TEXT, positions TEXT)
      one row per index key "lemma\\treading", rowid = index insertion order;
      unique index on (lemma, reading).
        lookup(lemma, reading)  -> positions of the (lemma, reading) row
        readings_for(lemma)     -> the lemma's rows ORDER BY rowid
  by_reading(reading TEXT PRIMARY KEY, positions TEXT)
      AccentIndex.by_reading for every reading where it is not None.

  positions = the list in preference order, comma-joined ("4,2").

DICTIONARY_FILE — sudachidict_full's system.dic, copied verbatim; Sudachi Java reads
the same binary format. Its version is a content hash."""
from __future__ import annotations

import hashlib
import os
import sqlite3
from importlib import metadata, resources
from pathlib import Path

from .jrp_fill.accent import AccentIndex

FORMAT = 1
ACCENT_FILE = "jrp-accent.sqlite"
DICTIONARY_FILE = "sudachi-system.dic"


def dictionary_source() -> tuple[Path, str] | None:
    """(system.dic path, "sudachidict_full <version>"), or None when the jrp
    dependency group is not installed."""
    try:
        path = Path(str(resources.files("sudachidict_full") / "resources" / "system.dic"))
        version = metadata.version("sudachidict-full")
    except (ModuleNotFoundError, metadata.PackageNotFoundError):
        return None
    return (path, f"sudachidict_full {version}") if path.is_file() else None


def build(out_dir, accent: AccentIndex | None = None,
          dictionary: tuple[Path, str] | None = None) -> dict | None:
    """Write ACCENT_FILE + DICTIONARY_FILE into out_dir and return the manifest's
    "sentence" entry; None (nothing written) without a Sudachi dictionary. Files
    whose content is unchanged are left alone."""
    dictionary = dictionary or dictionary_source()
    if dictionary is None:
        return None
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    return {
        "format": FORMAT,
        "accent": _build_accent(out, accent or AccentIndex.load()),
        "dictionary": _copy_dictionary(out, *dictionary),
    }


def _positions(values: list[int]) -> str:
    return ",".join(str(p) for p in values)


def _accent_rows(accent: AccentIndex):
    table = accent._t
    rows = []
    for key, positions in table.items():
        lemma, sep, reading = key.partition("\t")
        if not sep or "\t" in reading:
            raise ValueError(f"accent key {key!r} is not 'lemma<TAB>reading'")
        rows.append((lemma, reading, _positions(positions)))
    readings = list(dict.fromkeys(r for _, r, _ in rows))
    by_reading = [(rd, _positions(p)) for rd in readings
                  if (p := accent.by_reading(rd)) is not None]
    return rows, by_reading


def _build_accent(out: Path, accent: AccentIndex) -> dict:
    rows, by_reading = _accent_rows(accent)
    digest = hashlib.sha256(str(FORMAT).encode())
    for row in rows:
        digest.update("\t".join(row).encode("utf-8") + b"\n")
    digest.update(b"\0")
    for row in by_reading:
        digest.update("\t".join(row).encode("utf-8") + b"\n")
    version = digest.hexdigest()[:16]

    target = out / ACCENT_FILE
    if _accent_version(target) != version:
        tmp = out / (ACCENT_FILE + ".tmp")
        tmp.unlink(missing_ok=True)
        conn = sqlite3.connect(tmp)
        conn.executescript("""
            PRAGMA journal_mode = OFF;
            PRAGMA page_size = 4096;
            CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE accent (lemma TEXT NOT NULL, reading TEXT NOT NULL, positions TEXT NOT NULL);
            CREATE TABLE by_reading (reading TEXT PRIMARY KEY, positions TEXT NOT NULL) WITHOUT ROWID;
        """)
        conn.executemany("INSERT INTO accent VALUES (?,?,?)", rows)
        conn.execute("CREATE UNIQUE INDEX accent_key ON accent (lemma, reading)")
        conn.executemany("INSERT INTO by_reading VALUES (?,?)", by_reading)
        conn.executemany("INSERT INTO meta VALUES (?,?)", [
            ("format", str(FORMAT)), ("version", version), ("entries", str(len(rows)))])
        conn.commit()
        conn.execute("VACUUM")
        conn.close()
        os.replace(tmp, target)
    return {"file": ACCENT_FILE, "version": version, "size": target.stat().st_size,
            "sha256": file_sha256(target), "entries": len(rows)}


def _accent_version(path: Path) -> str | None:
    if not path.exists():
        return None
    try:
        conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        try:
            row = conn.execute("SELECT value FROM meta WHERE key='version'").fetchone()
        finally:
            conn.close()
    except sqlite3.Error:
        return None
    return row[0] if row else None


def _copy_dictionary(out: Path, source: Path, label: str) -> dict:
    sha = file_sha256(source)
    target = out / DICTIONARY_FILE
    size = source.stat().st_size
    if not (target.exists() and target.stat().st_size == size and file_sha256(target) == sha):
        tmp = out / (DICTIONARY_FILE + ".tmp")
        with open(source, "rb") as src, open(tmp, "wb") as dst:
            while chunk := src.read(1 << 20):
                dst.write(chunk)
        os.replace(tmp, target)
    return {"file": DICTIONARY_FILE, "version": sha[:16], "size": size, "sha256": sha,
            "source": label}


def file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()
