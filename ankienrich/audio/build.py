"""Locate the prebuilt term-audio store (var/audio.sqlite).

The store is the android.db packaged export (local-audio-yomichan /
AnkiConnect-Android format: `entries` + `android` tables, blobs embedded). It is
placed at var/audio.sqlite by hand (locally) or by deploy/push.sh (server) — there
is nothing to build at runtime. Runtime-read-only."""
import os

from .. import paths


def ensure(db_path=None) -> str:
    """'present' if the store exists, else 'no-source'. Never builds."""
    db_path = db_path or paths.AUDIO_DB
    return "present" if os.path.exists(db_path) else "no-source"
