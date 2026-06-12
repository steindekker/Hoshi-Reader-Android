"""Load and query the prebuilt accent index (source priority baked in)."""
from __future__ import annotations
import gzip
import marshal

from ankienrich.paths import ACCENT_INDEX as _DEFAULT


class AccentIndex:
    def __init__(self, table: dict[str, list[int]]):
        self._t = table
        self._rev: dict[str, dict[str, list[int]]] | None = None
        self._rread: dict[str, list[int]] | None = None

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

    def by_reading(self, reading_hira: str) -> list[int] | None:
        """Accent for an exact reading, ignoring surface — used only to keep a
        Sudachi-confident single word whole when the dict misses its surface but
        knows a same-shape homophone (開放感/解放感 -> かいほうかん[3]). Returns None
        unless every dict entry with this reading agrees on the accent, so
        ambiguous homophones (橋[2]/箸[1]) never produce a wrong guess."""
        if self._rread is None:
            seen: dict[str, set] = {}
            first: dict[str, list[int]] = {}
            for key, positions in self._t.items():
                rd = key.partition("\t")[2]
                # agreement is a set question, not an order one: the index is
                # preference-ordered, so [0,3] and [3,0] are the same accent info.
                seen.setdefault(rd, set()).add(frozenset(positions))
                first.setdefault(rd, positions)
            self._rread = {rd: first[rd] for rd, ts in seen.items() if len(ts) == 1}
        return self._rread.get(reading_hira)
