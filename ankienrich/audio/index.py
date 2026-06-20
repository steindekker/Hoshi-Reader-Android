"""Open + query the term-audio store (var/audio.sqlite)."""
import sqlite3

from ankienrich import paths
from ankienrich.dbutil import open_row_db


def open_index(path=None) -> sqlite3.Connection:
    """Read-only connection to audio.sqlite."""
    return open_row_db(path or paths.AUDIO_DB, readonly=True)
