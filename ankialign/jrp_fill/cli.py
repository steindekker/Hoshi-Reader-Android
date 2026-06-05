"""`uv run --group jrp python -m ankialign.jrp_fill [--apply]`"""
from __future__ import annotations
import argparse, os, shutil, sys, time

def _generate_factory(conj_accent: bool = True):
    from .accent import AccentIndex
    from .tokenize import units
    from .emit import emit_sentence
    acc = AccentIndex.load()
    return lambda text: emit_sentence(units(text, acc, conj_accent=conj_accent))

def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="ankialign.jrp_fill")
    ap.add_argument("--collection", default=os.path.expanduser(
        "~/projects/anki-align/collection.anki2"))
    ap.add_argument("--note-type", default="jidoujisho Kinomoto")
    ap.add_argument("--field", default="Sentence")
    ap.add_argument("--apply", action="store_true", help="write (default: dry-run)")
    ap.add_argument("--limit", type=int, default=0, help="cap notes for spot-checking")
    ap.add_argument("--no-conj-accent", dest="conj_accent", action="store_false",
                    help="show dict-form pitch instead of the computed conjugated "
                         "downstep (断られた -> ことわら\\れた is the default)")
    ap.add_argument("--regenerate", action="store_true",
                    help="rebuild EVERY field from its plain surface, replacing "
                         "existing accepted markup (default: splice into gaps only)")
    args = ap.parse_args(argv)

    if not os.path.exists(args.collection):
        print(f"no collection at {args.collection}", file=sys.stderr); return 1

    from anki.collection import Collection
    from . import engine

    if args.apply:
        bak = f"{args.collection}.{time.strftime('%Y%m%d-%H%M%S')}.bak"
        shutil.copy2(args.collection, bak)
        print(f"backup -> {bak}")

    col = Collection(args.collection)
    try:
        gen = _generate_factory(conj_accent=args.conj_accent)
        plan = engine.build_plan(col, args.note_type, args.field, gen,
                                 regenerate=args.regenerate)
        if args.limit:
            plan = plan[:args.limit]
        print(f"{len(plan)} notes would change")
        for fill in plan[:15]:
            print(f"\n  OLD {fill.old_value[:80]}\n  NEW {fill.new_value[:80]}")
        if args.apply:
            n = engine.apply_plan(col, plan, args.field)
            col.save()
            print(f"applied {n}")
        else:
            print("\n(dry-run; pass --apply to write)")
    finally:
        col.close()
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
