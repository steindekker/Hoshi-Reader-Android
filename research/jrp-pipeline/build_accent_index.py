#!/usr/bin/env python3
"""Build ankialign/data/accent_index.marshal.gz.

Key:   "lemma\treading_hira"  Value: sorted list[int] of downstep positions.
Source priority (later overwrites): NHK2016 -> アクセント辞典v2 -> user gold.
Run:   uv run python research/jrp-pipeline/build_accent_index.py
Reuses research/pitch-pipeline/work/validate.py.
"""
import os, sys, gzip, marshal

ROOT = os.path.expanduser("~/projects/anki-align")
sys.path.insert(0, os.path.join(ROOT, "research/pitch-pipeline/work"))
import validate as V

KATA = {chr(c): chr(c - 0x60) for c in range(0x30A1, 0x30F7)}
def hira(s): return "".join(KATA.get(c, c) for c in s)
def key(lemma, reading): return lemma + "\t" + hira(reading)

def main():
    index: dict[str, list[int]] = {}
    # NHK2016 then v2 (dict term_meta): (expr,reading)->set positions
    for name in ("NHK 2016 (Recommended)", "アクセント辞典v2"):
        m = V.load_dict(os.path.join(V.DICTD, name))
        for (expr, reading), positions in m.items():
            index[key(expr, reading)] = sorted(positions)
        print(f"after {name}: {len(index)} keys", file=sys.stderr)
    # gold wins
    gold, _, _ = V.extract_gold()
    for (expr, reading), (_yg, accs) in gold.items():
        index[key(expr, reading)] = sorted(accs)
    print(f"after gold: {len(index)} keys", file=sys.stderr)

    out = os.path.join(ROOT, "ankialign/data/accent_index.marshal.gz")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with gzip.open(out, "wb") as fh:
        fh.write(marshal.dumps(index))
    print(f"wrote {out} ({os.path.getsize(out)} bytes, {len(index)} keys)", file=sys.stderr)

if __name__ == "__main__":
    main()
