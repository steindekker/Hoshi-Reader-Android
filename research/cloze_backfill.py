#!/usr/bin/env python
"""Backfill empty Cloze Before/Inside/After on jidoujisho Kinomoto notes.

Thin CLI wrapper around `ankialign.cloze` (also run as part of `anki-align`).
Includes a validation pass: for notes that ALREADY have cloze, Before+Inside+After
must equal the reconstructed plain sentence — proving the markup strip matches
jidoujisho byte-for-byte.

Usage:  uv run --group jrp python research/cloze_backfill.py [--apply]
"""
from __future__ import annotations

import argparse
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, ROOT)
from ankialign import cloze, config            # noqa: E402
from ankialign.jrp_fill.accent import AccentIndex  # noqa: E402
from ankialign.pipeline import LIVE            # noqa: E402


def _validate(col) -> tuple[int, int, list]:
    """Cross-check the markup strip against notes that already have cloze."""
    m = col.models.by_name(config.TARGET_NOTETYPE)
    fld = {f["name"]: i for i, f in enumerate(m["flds"])}
    iS = fld["Sentence"]
    iB, iI, iA = fld["Cloze Before"], fld["Cloze Inside"], fld["Cloze After"]
    ok = bad = 0
    samples = []
    for nid in col.find_notes(f'note:"{config.TARGET_NOTETYPE}"'):
        f = col.get_note(nid).fields
        if not f[iI].strip():
            continue
        recon = f[iB] + f[iI] + f[iA]
        if recon == cloze.strip_markup(f[iS]):
            ok += 1
        else:
            bad += 1
            if len(samples) < 8:
                samples.append((recon[:60], cloze.strip_markup(f[iS])[:60]))
    return ok, bad, samples


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--collection", default=LIVE)
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args(argv)

    from anki.collection import Collection
    col = Collection(args.collection)
    try:
        ok, bad, samples = _validate(col)
        print(f"validation (existing cloze): {ok} match, {bad} mismatch")
        for a, b in samples:
            print(f"   recon={a!r}\n   plain={b!r}")
        res = cloze.run(col, apply=args.apply, acc=AccentIndex.load())
        print(f"\nbackfill: {res['filled']} filled  |  skipped (unlocatable): {res['skipped']}")
        print("applied." if args.apply else "(dry-run; pass --apply to write)")
    finally:
        col.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
