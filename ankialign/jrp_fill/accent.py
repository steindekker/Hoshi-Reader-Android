"""Load and query the prebuilt accent index (source priority baked in)."""
from __future__ import annotations
import os, gzip, marshal

_DEFAULT = os.path.join(os.path.dirname(__file__), "..", "data", "accent_index.marshal.gz")

class AccentIndex:
    def __init__(self, table: dict[str, list[int]]):
        self._t = table

    @classmethod
    def load(cls, path: str | None = None) -> "AccentIndex":
        path = path or _DEFAULT
        with gzip.open(path, "rb") as fh:
            return cls(marshal.loads(fh.read()))

    def lookup(self, lemma: str, reading_hira: str) -> list[int] | None:
        return self._t.get(lemma + "\t" + reading_hira)
