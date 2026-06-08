#!/usr/bin/env python3
"""Strip dead Yomitan ?query= cross-reference links from enriched definitions.

Thin CLI wrapper around `ankialign.query_links` (also run as part of
`anki-align`). Dry-run by default; --apply writes. Anki must be closed.

  uv run tools/fix_query_links.py                 # dry run, live collection
  uv run tools/fix_query_links.py --apply
  uv run tools/fix_query_links.py --collection PATH --apply
"""
import argparse
import os
import sys

from anki.collection import Collection

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from ankialign import query_links  # noqa: E402
from ankialign.pipeline import LIVE  # noqa: E402


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--collection", default=LIVE)
    ap.add_argument("--apply", action="store_true", help="write changes (else dry run)")
    args = ap.parse_args(argv)

    col = Collection(args.collection)
    try:
        res = query_links.run(col, apply=args.apply)
    finally:
        col.close()
    print(f"notes to change: {res['changed']}  (anchors unwrapped: {res['anchors']})")
    print("applied." if args.apply else "dry run — pass --apply to write.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
