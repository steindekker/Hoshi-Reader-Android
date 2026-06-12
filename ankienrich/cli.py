"""Console entry point: `anki-enrich`.

  uv run anki-enrich            # pull latest, dry-run preview of the suite
  uv run anki-enrich --apply    # pull, backup, write, push to AnkiWeb
  uv run anki-enrich --apply --overwrite   # re-assert pitch/sentences/defs/cloze
  uv run anki-enrich dedupe --apply         # destructive duplicate merge (separate)
  uv run anki-enrich template --apply       # apply repo card design to note type
"""
import argparse
import sys

from . import collection, dedupe as _dedupe, pipeline, sync, template

# tiny ANSI helpers — no deps, plain text when stdout is not a TTY
_TTY = sys.stdout.isatty()


def _c(code: str, s) -> str:
    return f"\033[{code}m{s}\033[0m" if _TTY else str(s)


def _bold(s): return _c("1", s)
def _dim(s): return _c("2", s)
def _cyan(s): return _c("36", s)
def _green(s): return _c("32", s)
def _red(s): return _c("31", s)


def _num(n) -> str:
    return _bold(_cyan(f"{n:>5}")) if n else _dim(f"{n:>5}")


def _row(label: str, big: str, detail: str = "") -> str:
    line = f"  {label.ljust(9)}{big}"
    return f"{line}   {_dim(detail)}" if detail else line


# human-readable sync outcomes, keyed by direction then action
_SYNC_TEXT = {
    "pull": {"none": "already up to date", "normal": "fetched changes",
             "download": "downloaded full collection"},
    "push": {"none": "nothing to upload", "normal": "uploaded changes",
             "upload": "uploaded full collection"},
}


def _sync_line(direction: str, action: str) -> None:
    text = _SYNC_TEXT.get(direction, {}).get(action, action)
    paint = _dim if action == "none" else _cyan
    print(_row("sync", paint(f"{direction} — {text}")))


def _render(res: dict, apply: bool) -> None:
    s = res["steps"]
    print()
    print(_row("scanned", f"{_bold(res['scanned'])} cards"))
    if res["backup"]:
        print(_row("backup", _dim(res["backup"])))
    print()
    a = s["audio"]
    if "skipped" in a:
        print(_row("audio", _dim("    —"), f"skipped: {a['skipped']}"))
    elif apply:
        filled, upgraded = sum(a["filled"].values()), sum(a["upgraded"].values())
        print(_row("audio", _num(filled), f"{upgraded} upgraded"))
    else:
        none = _red(f"{a['none']} no match") if a["none"] else _dim(f"{a['none']} no match")
        print(_row("audio", _num(a["will_fill"]), f"{a['will_upgrade']} to upgrade · ") + none)
    p = s["pitch"]
    print(_row("pitch", _num(p["filled"]),
               f"{p['not_found']} not in dict · {p['skipped']} no reading"))
    print(_row("sentence", _num(s["sentences"]["filled"]), "JRP markup"))
    e = s["enrich"]
    if "skipped" in e:
        print(_row("defs", _dim("    —"), f"skipped: {e['skipped']}"))
    elif apply:
        print(_row("defs", _num(e["written"]), f"{e['dropped']} dropped"))
    else:
        print(_row("defs", _num(e["to_write"]), f"{e['nohit']} no-hit"))
    x = s["examples"]
    if "skipped" in x:
        print(_row("examples", _dim("    —"), f"skipped: {x['skipped']}"))
    elif apply:
        print(_row("examples", _num(x["written"]), f"{x['none_tagged']} no-example"))
    else:
        print(_row("examples", _num(x["to_write"]), f"{x['none']} no-example"))
    c = s["cloze"]
    print(_row("cloze", _num(c["filled"]), f"{c['skipped']} unlocatable"))
    print()
    print("  " + (_green("✓ applied + pushed.") if apply
                  else _dim("dry run — pass --apply to write.")))
    print()


def _render_dedupe(res: dict, apply: bool) -> None:
    print()
    print(_row("scanned", f"{_bold(res['scanned'])} cards"))
    print(_row("groups", _num(res["groups"]), "duplicate groups"))
    print(_row("removable", _num(res["removed"])))
    print()
    print("  " + (_green("✓ merged + pushed.") if apply
                  else _dim("dry run — pass --apply to write.")))
    print()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="anki-enrich",
                                 description="Post-sync Anki enrichment.")
    ap.add_argument("command", nargs="?", default="run", choices=["run", "dedupe", "template"])
    ap.add_argument("--apply", action="store_true", help="write + push (else dry run)")
    ap.add_argument("--overwrite", action="store_true",
                    help="re-assert pitch, sentences, defs, examples AND cloze on every card")
    ap.add_argument("--overwrite-pitch", action="store_true")
    ap.add_argument("--overwrite-sentences", action="store_true")
    ap.add_argument("--overwrite-defs", action="store_true")
    ap.add_argument("--overwrite-examples", action="store_true")
    ap.add_argument("--overwrite-cloze", action="store_true")
    args = ap.parse_args(argv)

    col = collection.open_working()
    try:
        auth = sync.login(col)
        pull_action, auth = sync.pull(col, auth)
        _sync_line("pull", pull_action)
        if args.command == "dedupe":
            if args.apply:
                collection.backup(col)
            res = _dedupe.run(col, apply=args.apply)
            if args.apply:
                push_action, auth = sync.push(col, auth)
                _sync_line("push", push_action)
            _render_dedupe(res, args.apply)
        elif args.command == "template":
            if args.apply:
                collection.backup(col)
                template.apply(col)
                push_action, auth = sync.push(col, auth)
                _sync_line("push", push_action)
                print("\n  " + _green("✓ card design applied + pushed.") + "\n")
            else:
                print("\n  " + _dim("dry run — would apply repo card design "
                                     "(front/back/style.css). pass --apply to write.") + "\n")
        else:
            res = pipeline.run(
                col, apply=args.apply,
                overwrite_pitch=args.overwrite_pitch or args.overwrite,
                overwrite_sentences=args.overwrite_sentences or args.overwrite,
                overwrite_defs=args.overwrite_defs or args.overwrite,
                overwrite_examples=args.overwrite_examples or args.overwrite,
                overwrite_cloze=args.overwrite_cloze or args.overwrite)
            if args.apply:
                push_action, auth = sync.push(col, auth)
                _sync_line("push", push_action)
            _render(res, args.apply)
    finally:
        col.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
