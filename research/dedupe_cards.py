#!/usr/bin/env python
"""Merge duplicate jidoujisho Kinomoto notes (same Term+Reading).

Thin CLI wrapper around `ankialign.dedupe` (also exposed as `anki-align dedupe`).
Destructive: keeps the soonest-due card per Term+Reading, merges the others' review
stats onto it, then deletes the duplicates. Writes a TSV report. Backs up first on
--apply. After running, sync; if Anki flags a conflict choose "Upload to AnkiWeb".

Usage:  uv run --group jrp python research/dedupe_cards.py [--apply]
"""
from __future__ import annotations

import argparse
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, ROOT)
from ankialign import convert, dedupe          # noqa: E402
from ankialign.pipeline import LIVE            # noqa: E402

REPORT = os.path.join(os.path.dirname(__file__), "dedupe_report.tsv")


def _write_report(merges) -> None:
    with open(REPORT, "w", encoding="utf-8") as fh:
        fh.write("term\treading\tkeep_due\tkeep_reps\tmerged_reps\tdropped_dues\n")
        for key, keep, drop in merges:
            fh.write(f"{key[0]}\t{key[1]}\t{keep['due']}\t{keep['reps']}\t"
                     f"{sum(d['reps'] for d in drop)}\t"
                     f"{','.join(str(d['due']) for d in drop)}\n")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--collection", default=LIVE)
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args(argv)

    from anki.collection import Collection
    col = Collection(args.collection)
    try:
        if args.apply:
            backup_dir = os.path.join(os.path.dirname(col.path), "backups")
            convert.backup_collection(col, backup_dir)
            print("backup ->", backup_dir)
        res = dedupe.run(col, apply=args.apply)
        _write_report(res["merges"])
        print(f"dup groups: {res['groups']} | removable notes: {res['removed']}")
        print(f"report -> {REPORT}")
        for key, keep, drop in res["merges"][:8]:
            print(f"  {key[0]}/{key[1]}: keep due={keep['due']} reps={keep['reps']} "
                  f"(+{sum(d['reps'] for d in drop)} merged)  drop dues={[d['due'] for d in drop]}")
        if args.apply:
            print(f"\nremoved {res['removed']} notes (revlog rows moved: {res['revlog_moved']})")
        else:
            print("\n(dry-run; pass --apply to write)")
    finally:
        col.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
