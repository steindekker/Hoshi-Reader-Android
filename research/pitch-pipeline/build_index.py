"""Build the compact pitch index from アクセント辞典v2 (primary accent only).

Reads the Yomitan term_meta banks (absolute path in the main checkout, since
the extracted dict is gitignored) and writes ankialign/data/pitch_index.marshal.gz
keyed by "term\treading_hira" -> primary downstep position.

Run once:  uv run python research/pitch-pipeline/build_index.py
"""
import glob
import gzip
import json
import marshal
import os
import sys

# pure helper, reused from the shipped module
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "ankialign"))
from pitch_index import to_hira  # noqa: E402

SRC = "~/projects/anki-align/research/pitch-pipeline/dicts/d/アクセント辞典v2"
OUT = os.path.join(os.path.dirname(__file__), "..", "..",
                   "ankialign", "data", "pitch_index.marshal.gz")


def main() -> None:
    banks = sorted(glob.glob(os.path.join(SRC, "term_meta_bank_*.json")))
    if not banks:
        raise SystemExit(f"no term_meta banks under {SRC}")
    idx: dict[str, int] = {}
    for bank in banks:
        with open(bank, encoding="utf-8") as fh:
            data = json.load(fh)
        for e in data:
            if len(e) < 3 or e[1] != "pitch":
                continue
            term, info = e[0], e[2]
            reading = info.get("reading", term)
            pitches = info.get("pitches", [])
            if not pitches:
                continue
            pos = pitches[0].get("position")   # pitches[0] = primary
            if not isinstance(pos, int):
                continue
            idx.setdefault(f"{term}\t{to_hira(reading)}", pos)  # first wins
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with gzip.open(OUT, "wb", compresslevel=9) as f:
        f.write(marshal.dumps(idx))
    size = os.path.getsize(OUT)
    print(f"wrote {len(idx)} keys -> {OUT} ({size/1e6:.1f} MB)")


if __name__ == "__main__":
    main()
