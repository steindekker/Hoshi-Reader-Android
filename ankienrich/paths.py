"""Filesystem locations. Repo-root relative so `uv run` from the project works."""
import os

_PKG = os.path.dirname(__file__)
ROOT = os.path.dirname(_PKG)

DATA_DIR = os.path.join(_PKG, "data")
ACCENT_INDEX = os.path.join(DATA_DIR, "accent_index.marshal.gz")

VAR_DIR = os.path.join(ROOT, "var")
DEFS_DB = os.path.join(VAR_DIR, "defs.sqlite")
EXAMPLES_DB = os.path.join(VAR_DIR, "examples.sqlite")
DICT_STYLES = os.path.join(VAR_DIR, "dict_styles.css")
WORKING_COLLECTION = os.path.join(VAR_DIR, "collection.anki2")

DICTS_DIR = os.path.join(ROOT, "dicts")
TATOEBA_DIR = os.path.join(DICTS_DIR, "tatoeba")
CARD_DIR = os.path.join(ROOT, "card", "kinomoto")


def ensure_var() -> str:
    os.makedirs(VAR_DIR, exist_ok=True)
    return VAR_DIR
