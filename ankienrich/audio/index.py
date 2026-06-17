"""Open + query the term-audio store (var/audio.sqlite)."""
import sqlite3

from ankienrich import paths


def open_index(path=None) -> sqlite3.Connection:
    """Read-only connection to audio.sqlite."""
    p = path or paths.AUDIO_DB
    conn = sqlite3.connect(f"file:{p}?mode=ro", uri=True)
    return conn
