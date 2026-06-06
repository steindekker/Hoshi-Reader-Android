#!/usr/bin/env python3
"""Strip dead Yomitan cross-reference links from enriched definitions.

三省堂 (and other Yomitan-imported) defs carry relative anchors like
`<a href="?query=WORD&wildcards=off">WORD</a>`. In any Anki/AnkiDroid webview
these resolve to a dead `http://127.0.0.1:<port>/?query=…`. Unwrap them to the
inner text so the word becomes plain *selectable* text — which AnkiDroid's text
selection can hand to jidoujisho (its PROCESS_TEXT lookup). Absolute links
(e.g. `https://maps.apple.com/…` geo-refs) are left clickable.

Dry-run by default; pass --apply to write. Edits via the Collection API so note
mod/usn are bumped and the change syncs to AnkiDroid normally. Anki must be
closed. Idempotent.

  uv run tools/fix_query_links.py                 # dry run, live collection
  uv run tools/fix_query_links.py --apply
  uv run tools/fix_query_links.py --collection PATH --apply
"""
import argparse
import os
import re
import sys

from anki.collection import Collection

LIVE = os.path.expanduser("~/.local/share/Anki2/User 1/collection.anki2")
NOTETYPE = "jidoujisho Kinomoto"
# Only the rendered, *displayed* definition. "Original Meaning" is the archived
# pre-enrichment backup (with its own yomitan-media anchors) — leave it intact.
FIELD = "Meaning"

_ANCHOR = re.compile(r"<a\b([^>]*)>(.*?)</a>", re.S)  # anchors don't nest
_HREF = re.compile(r'href="([^"]*)"')


def _unwrap(m: re.Match) -> str:
    href_m = _HREF.search(m.group(1))
    href = href_m.group(1) if href_m else ""
    if "://" in href:            # absolute -> keep clickable
        return m.group(0)
    return m.group(2)            # relative/internal -> drop wrapper, keep text


def fix_field(val: str) -> str:
    return _ANCHOR.sub(_unwrap, val)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--collection", default=LIVE)
    ap.add_argument("--apply", action="store_true", help="write changes (else dry run)")
    args = ap.parse_args(argv)

    col = Collection(args.collection)
    try:
        mid = col.models.id_for_name(NOTETYPE)
        if mid is None:
            print(f"notetype not found: {NOTETYPE}", file=sys.stderr)
            return 1
        nids = col.find_notes(f'"note:{NOTETYPE}"')
        changed, anchors = [], 0
        for nid in nids:
            note = col.get_note(nid)
            if FIELD not in note:
                continue
            val = note[FIELD]
            new = fix_field(val)
            if new != val:
                anchors += len(_ANCHOR.findall(val)) - len(_ANCHOR.findall(new))
                note[FIELD] = new
                changed.append(note)
        print(f"notes in {NOTETYPE}: {len(nids)}")
        print(f"notes to change: {len(changed)}  (anchors unwrapped: {anchors})")
        if args.apply and changed:
            col.update_notes(changed)
            print("applied.")
        elif not args.apply:
            print("dry run — pass --apply to write.")
        return 0
    finally:
        col.close()


if __name__ == "__main__":
    raise SystemExit(main())
