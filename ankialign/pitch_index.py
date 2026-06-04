"""Pure pitch-accent lookup over a prebuilt index. No anki/aqt imports.

Index format: a dict { "term\treading_hira": position } serialized with
stdlib marshal + gzip (see research/pitch-pipeline/build_index.py).
"""
import gzip
import marshal


def to_hira(s: str) -> str:
    """Katakana -> hiragana (1:1). Non-kana (incl. ー) pass through."""
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in s)


class PitchIndex:
    def __init__(self, mapping: dict):
        self._m = mapping

    @classmethod
    def load(cls, path: str) -> "PitchIndex":
        with gzip.open(path, "rb") as f:
            return cls(marshal.loads(f.read()))

    def lookup(self, term: str, reading: str):
        """Primary downstep position for (term, reading), or None.

        Tries: exact (term, reading_hira); then kana-headword fallbacks where
        the dict headword is the reading itself.
        """
        r = to_hira(reading.strip())
        term = term.strip()
        for key in (f"{term}\t{r}", f"{reading.strip()}\t{r}", f"{r}\t{r}"):
            if key in self._m:
                return self._m[key]
        return None
