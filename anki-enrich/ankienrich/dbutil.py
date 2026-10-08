"""Shared SQLite + Yomitan-bank helpers used by the build/index passes."""
import sqlite3
from pathlib import Path


def open_row_db(path, *, readonly: bool = False) -> sqlite3.Connection:
    """sqlite3 connection with a Row factory; `readonly` opens via a file: URI."""
    if readonly:
        conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    else:
        conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    return conn


def by_reading(rows, reading):
    """Rows matching the note reading; fall back to reading-less, then all."""
    exact = [r for r in rows if r["reading"] == reading]
    if exact:
        return exact
    blank = [r for r in rows if r["reading"] == ""]
    return blank or rows


def term_banks(dicts_dir, dict_id, pattern: str = "term_bank_*.json") -> list[Path]:
    """Sorted Yomitan bank files for one dict (term_bank_* by default)."""
    return sorted(Path(dicts_dir, dict_id).glob(pattern))
