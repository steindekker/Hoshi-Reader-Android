"""Console-script entry point: `anki-align`.

  uv run anki-align            # dry-run preview of the enrichment suite
  uv run anki-align --apply    # backup, then write; sync afterward
  uv run anki-align --apply --overwrite   # re-assert defs, pitch, sentences, cloze
  uv run anki-align dedupe --apply         # destructive duplicate merge (separate)

By default defs, pitch graphs, JRP sentence markup and cloze are written only on
cards that lack them, so manual edits survive a rerun. --overwrite-defs /
--overwrite-pitch / --overwrite-sentences / --overwrite-cloze (or --overwrite for
all four) re-assert the value on every card, writing only where it differs.
"""
import argparse
import os
import sys

from anki.collection import Collection

from . import convert
from . import dedupe as _dedupe
from . import pipeline

# --- tiny ANSI helpers (no deps; plain text when not a TTY) ---------------------
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


def _kv(key: str, val: str) -> str:
    return f"  {_dim(key.ljust(11))}{val}"


def _row(label: str, big: str, detail: str = "") -> str:
    # plain-text columns (no emoji) so the number column always lines up
    line = f"  {label.ljust(8)}{big}"
    return f"{line}   {_dim(detail)}" if detail else line


# --- per-step rendering ---------------------------------------------------------
def _render_audio(info: dict, apply: bool) -> str:
    if "skipped" in info:
        return _row("audio", _dim("    —"), f"skipped: {info['skipped']}")
    if apply:
        filled = info["filled"]
        total = sum(filled.values())
        src = " · ".join(f"{k} {v}" for k, v in filled.items()) or "none"
        return _row("audio", _num(total), src + (
            _red(f"  · {info['errors']} errors") if info.get("errors") else ""))
    src = " · ".join(f"{k} {v}" for k, v in info["by_source"].items()) or "none"
    return _row("audio", _num(info["will_fill"]), f"{src} · {info['none']} no match")


def _render_pitch(info: dict) -> str:
    return _row("pitch", _num(info["filled"]),
                f"{info['not_found']} not in dict · {info['skipped']} no reading")


def _render_enrich(info: dict, apply: bool) -> str:
    if "skipped" in info:
        return _row("defs", _dim("    —"), f"skipped: {info['skipped']}")
    if apply:
        return _row("defs", _num(info["written"]),
                    f"{info['nohit_tagged']} no-hit tagged · {info['dropped']} dropped")
    return _row("defs", _num(info["to_write"]),
                f"{info['enrich']} J-J · {info['english']} EN-only · {info['nohit']} no-hit")


def _render_sentences(info: dict) -> str:
    return _row("sentence", _num(info["filled"]), "JRP markup filled")


def _render_links(info: dict) -> str:
    return _row("links", _num(info["changed"]), f"{info['anchors']} dead anchors unwrapped")


def _render_cloze(info: dict) -> str:
    return _row("cloze", _num(info["filled"]), f"{info['skipped']} unlocatable")


def _print_rerun(res: dict, apply: bool) -> None:
    src = res["sources"]
    dicts = src["dicts"]
    if res["def_index"] == "no-source":
        dicts += _red("   — index missing, enrichment skipped")
    elif res["def_index"] == "built":
        dicts += _dim("   (index rebuilt)")
    print()
    print(_kv("dicts", dicts))
    print(_kv("audio", src["audio"]))
    print(_kv("pitch", src["pitch"]))
    print()
    print(_kv("scanned", f"{_bold(res['scanned'])} cards"))
    overwriting = [n for n, k in (("defs", "overwrite_defs"), ("pitch", "overwrite_pitch"),
                                  ("sentences", "overwrite_sentences"),
                                  ("cloze", "overwrite_cloze")) if res.get(k)]
    if overwriting:
        print(_kv("mode", _red("overwrite " + ", ".join(overwriting))))
    if res["backup"]:
        print(_kv("backup", res["backup"]))
    print()
    s = res["steps"]
    print(_render_audio(s["audio"], apply))
    print(_render_pitch(s["pitch"]))
    print(_render_sentences(s["sentences"]))
    print(_render_enrich(s["enrich"], apply))
    print(_render_links(s["query_links"]))
    print(_render_cloze(s["cloze"]))
    print()
    if apply:
        print(f"  {_green('✓ done.')} Open Anki and sync — if prompted, choose "
              f"{_bold('Upload to AnkiWeb')} {_dim('(enrich adds fields → full sync)')}.")
    else:
        print(f"  {_dim('dry run — pass')} {_bold('--apply')} {_dim('to write.')}")
    print()


def _print_dedupe(res: dict, apply: bool, backup) -> None:
    print()
    print(_kv("scanned", f"{_bold(res['scanned'])} cards"))
    if backup:
        print(_kv("backup", backup))
    print(_kv("dup groups", _bold(res["groups"])))
    print(_kv("removable", _bold(res["removed"])))
    print()
    for key, keep, drop in res["merges"][:12]:
        merged = sum(d["reps"] for d in drop)
        drop_dues = [d["due"] for d in drop]
        detail = (f"keep due={keep['due']} reps={keep['reps']} "
                  f"+{merged} merged · drop dues={drop_dues}")
        print(f"  {_cyan(key[0])}/{key[1]}  {_dim(detail)}")
    print()
    if apply:
        moved = res["revlog_moved"]
        print(f"  {_green('✓ removed')} {res['removed']} duplicate(s) "
              f"{_dim(f'(revlog rows moved: {moved})')}. "
              f"Sync; if Anki flags a conflict, {_bold('Upload to AnkiWeb')}.")
    else:
        print(f"  {_dim('dry run — pass')} {_bold('--apply')} {_dim('to write.')}")
    print()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="anki-align",
                                 description="Repeatable post-sync Anki enrichment.")
    ap.add_argument("command", nargs="?", default="rerun", choices=["rerun", "dedupe"],
                    help="rerun (default): full enrichment suite; dedupe: merge duplicates")
    ap.add_argument("--collection", default=pipeline.LIVE)
    ap.add_argument("--apply", action="store_true", help="write changes (else dry run)")
    ap.add_argument("--overwrite", action="store_true",
                    help="re-assert defs, pitch, sentences AND cloze on every card "
                         "(= all four --overwrite-* flags below)")
    ap.add_argument("--overwrite-defs", action="store_true",
                    help="re-assert dictionary definitions on ALL cards, clobbering "
                         "manual edits (default: only enrich never-enriched cards)")
    ap.add_argument("--overwrite-pitch", action="store_true",
                    help="re-render pitch graphs on cards that already have one, "
                         "clobbering stale/hand-edited ones (default: only fill empty)")
    ap.add_argument("--overwrite-sentences", action="store_true",
                    help="rebuild JRP markup on EVERY Sentence from its plain surface, "
                         "replacing accepted gold (default: splice into plain gaps only)")
    ap.add_argument("--overwrite-cloze", action="store_true",
                    help="re-split the Sentence into cloze on cards that already have "
                         "one, clobbering edits (default: only fill empty cloze)")
    args = ap.parse_args(argv)

    col = Collection(args.collection)
    try:
        if args.command == "dedupe":
            backup_dir = None
            if args.apply:
                backup_dir = os.path.join(os.path.dirname(col.path), "backups")
                convert.backup_collection(col, backup_dir)
            res = _dedupe.run(col, apply=args.apply)
            _print_dedupe(res, args.apply, backup_dir)
        else:
            res = pipeline.run(
                col, apply=args.apply,
                overwrite_defs=args.overwrite_defs or args.overwrite,
                overwrite_pitch=args.overwrite_pitch or args.overwrite,
                overwrite_cloze=args.overwrite_cloze or args.overwrite,
                overwrite_sentences=args.overwrite_sentences or args.overwrite)
            _print_rerun(res, args.apply)
    finally:
        col.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
