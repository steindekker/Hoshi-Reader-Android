"""Console entry point: `anki-align`.

  uv run anki-align            # pull latest, dry-run preview of the suite
  uv run anki-align --apply    # pull, backup, write, push to AnkiWeb
  uv run anki-align --apply --overwrite   # re-assert pitch/sentences/defs/cloze
  uv run anki-align dedupe --apply         # destructive duplicate merge (separate)
"""
import argparse

from . import collection, dedupe as _dedupe, pipeline, sync


def _render(res: dict, apply: bool) -> None:
    s = res["steps"]
    print(f"\n  scanned   {res['scanned']} cards")
    if res["backup"]:
        print(f"  backup    {res['backup']}")
    print()
    a = s["audio"]
    if "skipped" in a:
        print(f"  audio     — skipped: {a['skipped']}")
    elif apply:
        print(f"  audio     {sum(a['filled'].values())} filled")
    else:
        print(f"  audio     {a['will_fill']} to fill · {a['none']} no match")
    p = s["pitch"]
    print(f"  pitch     {p['filled']} · {p['not_found']} not in dict · {p['skipped']} no reading")
    print(f"  sentence  {s['sentences']['filled']} JRP markup")
    e = s["enrich"]
    if "skipped" in e:
        print(f"  defs      — skipped: {e['skipped']}")
    elif apply:
        print(f"  defs      {e['written']} written · {e['dropped']} dropped")
    else:
        print(f"  defs      {e['to_write']} to write · {e['nohit']} no-hit")
    print(f"  cloze     {s['cloze']['filled']} · {s['cloze']['skipped']} unlocatable")
    print()
    print("  ✓ applied + pushed." if apply else "  dry run — pass --apply to write.")
    print()


def _render_dedupe(res: dict, apply: bool) -> None:
    print(f"\n  scanned    {res['scanned']} cards")
    print(f"  dup groups {res['groups']}")
    print(f"  removable  {res['removed']}")
    print("\n  ✓ merged + pushed." if apply else "\n  dry run — pass --apply to write.")
    print()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="anki-align",
                                 description="Post-sync Anki enrichment.")
    ap.add_argument("command", nargs="?", default="rerun", choices=["rerun", "dedupe"])
    ap.add_argument("--apply", action="store_true", help="write + push (else dry run)")
    ap.add_argument("--overwrite", action="store_true",
                    help="re-assert pitch, sentences, defs AND cloze on every card")
    ap.add_argument("--overwrite-pitch", action="store_true")
    ap.add_argument("--overwrite-sentences", action="store_true")
    ap.add_argument("--overwrite-defs", action="store_true")
    ap.add_argument("--overwrite-cloze", action="store_true")
    args = ap.parse_args(argv)

    col = collection.open_working()
    try:
        auth = sync.login(col)
        print(f"  sync      pull: {sync.pull(col, auth)}")
        if args.command == "dedupe":
            if args.apply:
                collection.backup(col)
            res = _dedupe.run(col, apply=args.apply)
            if args.apply:
                print(f"  sync      push: {sync.push(col, auth)}")
            _render_dedupe(res, args.apply)
        else:
            res = pipeline.run(
                col, apply=args.apply,
                overwrite_pitch=args.overwrite_pitch or args.overwrite,
                overwrite_sentences=args.overwrite_sentences or args.overwrite,
                overwrite_defs=args.overwrite_defs or args.overwrite,
                overwrite_cloze=args.overwrite_cloze or args.overwrite)
            if args.apply:
                print(f"  sync      push: {sync.push(col, auth)}")
            _render(res, args.apply)
    finally:
        col.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
