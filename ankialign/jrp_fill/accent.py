"""Load and query the prebuilt accent index (source priority baked in)."""
from __future__ import annotations
import os, gzip, marshal

_DEFAULT = os.path.join(os.path.dirname(__file__), "..", "data", "accent_index.marshal.gz")

class AccentIndex:
    def __init__(self, table: dict[str, list[int]]):
        self._t = table
        self._rev: dict[str, dict[str, list[int]]] | None = None

    @classmethod
    def load(cls, path: str | None = None) -> "AccentIndex":
        path = path or _DEFAULT
        with gzip.open(path, "rb") as fh:
            return cls(marshal.loads(fh.read()))

    def lookup(self, lemma: str, reading_hira: str) -> list[int] | None:
        return self._t.get(lemma + "\t" + reading_hira)

    def readings_for(self, lemma: str) -> dict[str, list[int]]:
        """All readings the dict knows for a word -> {reading_hira: positions}.
        Lets the dict disambiguate a reading Sudachi got wrong (綾 -> あや, not
        りょう). Reverse index built once on first use."""
        if self._rev is None:
            rev: dict[str, dict[str, list[int]]] = {}
            for key, positions in self._t.items():
                lem, _, rd = key.partition("\t")
                rev.setdefault(lem, {})[rd] = positions
            self._rev = rev
        return self._rev.get(lemma, {})
