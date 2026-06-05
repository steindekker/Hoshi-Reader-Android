#!/usr/bin/env python3
"""Build ankialign/data/pitch_accent_index.marshal.gz — the DICT-AUTHORITATIVE
accent index used only by the term pitch-graph backfill.

Unlike the shared JRP index (NHK -> v2 -> gold, gold wins), the pitch graph wants
the dictionary's canonical accents/order for a word in isolation, and treats the
user's unverified auto-gold only as a coverage filler for words the dicts lack.

Priority (later overwrites): user gold -> NHK2016 -> アクセント辞典v2.
  => v2 wins for words it has; NHK fills v2-misses; gold fills both-misses only.

Key:   "lemma\treading_hira"  Value: preference-ordered list[int] (pitches[0] = primary).
Run:   uv run python research/pitch-pipeline/build_pitch_index.py
"""
import os, sys, gzip, marshal

# worktree-safe: write into THIS checkout, import this tree's validate.py.
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "research/pitch-pipeline/work"))
sys.path.insert(0, ROOT)
import validate as V
from ankialign.jrp_fill._utils import hira


def key(lemma, reading):
    return lemma + "\t" + hira(reading)


def main():
    index: dict[str, list[int]] = {}
    # gold first (lowest priority: survives only where both dicts miss the word)
    for (expr, reading), accs in V.extract_gold_ordered().items():
        index[key(expr, reading)] = accs
    print(f"after gold (coverage filler): {len(index)} keys", file=sys.stderr)
    # dictionaries overwrite gold; v2 last so it wins over NHK
    for name in ("NHK 2016 (Recommended)", "アクセント辞典v2"):
        m = V.load_dict_ordered(os.path.join(V.DICTD, name))
        for (expr, reading), positions in m.items():
            index[key(expr, reading)] = positions
        print(f"after {name}: {len(index)} keys", file=sys.stderr)

    out = os.path.join(ROOT, "ankialign/data/pitch_accent_index.marshal.gz")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with gzip.open(out, "wb", compresslevel=9) as fh:
        fh.write(marshal.dumps(index))
    print(f"wrote {out} ({os.path.getsize(out)} bytes, {len(index)} keys)", file=sys.stderr)


if __name__ == "__main__":
    main()
