"""Gap-fill the shipped accent index from extra Yomitan pitch dictionaries.

The base index (アクセント辞典v2 + NHK2016) ships prebuilt at
`data/accent_index.marshal.gz`; its original sources aren't in the repo, so we
can't rebuild it from scratch. Instead this AUGMENTS it: it only ADDS entries
for (term, reading) keys the index currently misses, never touching an existing
one — so accuracy is unchanged and coverage can only rise.

Source dicts live under `dicts/pitch_*` (gitignored, local-only), in Yomitan
term_meta_bank pitch format. Idempotent: re-running adds nothing new.

    uv run python -m ankienrich.accent_augment            # rewrite the index in place
    uv run python -m ankienrich.accent_augment --dry-run  # report only

三省堂第八版 was chosen as the first extra source: of four candidates it had the
highest inter-dict consensus accuracy (98.3%) and the most incremental deck
coverage. Two add modes, both safe:
  - term-keyed: an exact (term, reading) match the index lacks.
  - kana-key (reading,reading): only for readings where every entry in the
    source agrees on the accent, so homophones (橋/箸) never inject a wrong guess.
"""
from __future__ import annotations
import gzip
import json
import glob
import marshal
import os
import sys
from collections import defaultdict

from .jrp_fill.kana import hira
from .paths import ACCENT_INDEX, DICTS_DIR

PITCH_SOURCES = ("pitch_sanseido8",)   # dirs under dicts/, priority order


def _dedup(seq):
    out = []
    for x in seq:
        if x not in out:
            out.append(x)
    return out


def _load_source(name: str) -> dict[tuple[str, str], list[int]]:
    """(term, hira reading) -> preference-ordered downstep positions."""
    pair: dict[tuple[str, str], list[int]] = {}
    banks = glob.glob(os.path.join(DICTS_DIR, name, "term_meta_bank_*.json"))
    for f in banks:
        for term, typ, info in json.load(open(f, encoding="utf-8")):
            if typ != "pitch":
                continue
            r = hira(info["reading"])
            ps = [p["position"] for p in info.get("pitches", []) if "position" in p]
            if ps:
                pair.setdefault((term, r), []).extend(ps)
    return {k: _dedup(v) for k, v in pair.items()}


def augment(table: dict[str, list[int]]) -> tuple[dict[str, list[int]], dict]:
    """Return (new_table, stats). new_table is a superset of `table`."""
    add_pair: dict[str, list[int]] = {}
    add_kana: dict[str, list[int]] = {}
    for name in PITCH_SOURCES:
        src = _load_source(name)
        for (t, r), ps in src.items():
            k = f"{t}\t{r}"
            if k not in table and k not in add_pair:
                add_pair[k] = ps
        # kana key only where the whole source agrees on the reading's accent
        by_reading: dict[str, set] = defaultdict(set)
        for (t, r), ps in src.items():
            by_reading[r].add(tuple(ps))
        for r, variants in by_reading.items():
            if len(variants) != 1:
                continue
            k = f"{r}\t{r}"
            if k not in table and k not in add_pair and k not in add_kana:
                add_kana[k] = list(next(iter(variants)))
    new_table = {**table, **add_pair, **add_kana}
    assert all(new_table[k] == v for k, v in table.items()), "existing entry mutated"
    return new_table, {"base": len(table), "term": len(add_pair), "kana": len(add_kana)}


def main(argv=None) -> int:
    argv = argv if argv is not None else sys.argv[1:]
    dry = "--dry-run" in argv
    with gzip.open(ACCENT_INDEX, "rb") as fh:
        table = marshal.loads(fh.read())
    new_table, stats = augment(table)
    added = stats["term"] + stats["kana"]
    print(f"base {stats['base']} + term {stats['term']} + kana {stats['kana']} "
          f"= {len(new_table)} (+{added})")
    if dry:
        print("dry-run: index not written")
        return 0
    if not added:
        print("nothing to add (already augmented)")
        return 0
    with gzip.open(ACCENT_INDEX, "wb") as fh:
        fh.write(marshal.dumps(new_table))
    print(f"wrote {ACCENT_INDEX}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
