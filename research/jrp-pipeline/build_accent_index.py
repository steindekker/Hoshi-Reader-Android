#!/usr/bin/env python3
"""Build ankialign/data/accent_index.marshal.gz.

Key:   "lemma\treading_hira"  Value: preference-ordered list[int] of downstep
       positions (pitches[0]/markup-first = primary; NOT sorted).
Source priority (later overwrites): NHK2016 -> アクセント辞典v2 -> user gold.
Run:   uv run python research/jrp-pipeline/build_accent_index.py
Reuses research/pitch-pipeline/work/validate.py.
"""
import os, sys, gzip, marshal

# Repo root of THIS checkout (worktree-safe): write output + import this tree's
# validate.py. Input data (dicts, collection) resolve via validate.py's own paths
# to where the gitignored sources live.
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "research/pitch-pipeline/work"))
sys.path.insert(0, ROOT)
import validate as V
from ankialign.jrp_fill._utils import hira

def key(lemma, reading): return lemma + "\t" + hira(reading)

def main():
    index: dict[str, list[int]] = {}
    # NHK2016 then v2 (dict term_meta): (expr,reading)->preference-ordered positions
    # (pitches[0] = the dictionary's primary accent; order is preserved, not sorted).
    for name in ("NHK 2016 (Recommended)", "アクセント辞典v2"):
        m = V.load_dict_ordered(os.path.join(V.DICTD, name))
        for (expr, reading), positions in m.items():
            index[key(expr, reading)] = positions
        print(f"after {name}: {len(index)} keys", file=sys.stderr)
    # gold wins — keep the user's own markup order (their preferred accent first)
    gold = V.extract_gold_ordered()
    for (expr, reading), accs in gold.items():
        index[key(expr, reading)] = accs
    print(f"after gold: {len(index)} keys", file=sys.stderr)

    out = os.path.join(ROOT, "ankialign/data/accent_index.marshal.gz")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with gzip.open(out, "wb") as fh:
        fh.write(marshal.dumps(index))
    print(f"wrote {out} ({os.path.getsize(out)} bytes, {len(index)} keys)", file=sys.stderr)

if __name__ == "__main__":
    main()
