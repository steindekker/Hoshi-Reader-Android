"""Filesystem locations. Repo-root relative so `uv run` from the project works.

Override the working data dir with ANKI_ENRICH_VAR (e.g. /opt/anki-enrich/data on
box) so a source checkout shares the live collection/media/indexes/lock with
the docker install — do not invent a second tree.
"""
from __future__ import annotations

import os

_PKG = os.path.dirname(__file__)
ROOT = os.path.dirname(_PKG)

DATA_DIR = os.path.join(_PKG, "data")
ACCENT_INDEX = os.path.join(DATA_DIR, "accent_index.marshal.gz")

_DEFAULT_VAR = os.path.join(ROOT, "var")

DICTS_DIR = os.path.join(ROOT, "dicts")
TATOEBA_DIR = os.path.join(DICTS_DIR, "tatoeba")
CARD_DIR = os.path.join(ROOT, "card", "kinomoto")


def var_dir() -> str:
    """Working dir for collection, media, sqlite indexes, and run.lock."""
    return os.environ.get("ANKI_ENRICH_VAR", _DEFAULT_VAR)


def working_collection() -> str:
    return os.path.join(var_dir(), "collection.anki2")


def run_lock_path() -> str:
    return os.path.join(var_dir(), "run.lock")


def ensure_var() -> str:
    d = var_dir()
    os.makedirs(d, exist_ok=True)
    return d


def __getattr__(name: str):
    """Lazy aliases so `paths.VAR_DIR` / `paths.DEFS_DB` track ANKI_ENRICH_VAR."""
    if name == "VAR_DIR":
        return var_dir()
    if name == "WORKING_COLLECTION":
        return working_collection()
    if name == "RUN_LOCK":
        return run_lock_path()
    if name == "DEFS_DB":
        return os.path.join(var_dir(), "defs.sqlite")
    if name == "EXAMPLES_DB":
        return os.path.join(var_dir(), "examples.sqlite")
    if name == "AUDIO_DB":
        return os.path.join(var_dir(), "audio.sqlite")
    if name == "FREQ_DB":
        return os.path.join(var_dir(), "freq.sqlite")
    if name == "DICT_STYLES":
        return os.path.join(var_dir(), "dict_styles.css")
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
