"""Console entry point: `anki-enrich`.

  uv run anki-enrich            # pull latest, dry-run preview of the suite
  uv run anki-enrich --apply    # pull, backup, write, push to AnkiWeb
  uv run anki-enrich --apply --overwrite   # re-assert pitch/sentences/defs/cloze
  uv run anki-enrich dedupe --apply         # destructive duplicate merge (separate)
  uv run anki-enrich template --apply       # apply repo card design to note type
"""
import argparse
import sys

from . import collection, dedupe as _dedupe, notify, pipeline, runlock, sync, template, wizard

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


# Newly-enriched terms listed inline; beyond this just show the count.
_TERM_CAP = 50


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
        filled = sum(a["filled"].values())
        print(_row("audio", _num(filled), f"{a['errors']} errors" if a["errors"] else ""))
    else:
        none = _red(f"{a['none']} no match") if a["none"] else _dim(f"{a['none']} no match")
        print(_row("audio", _num(a["will_fill"]), none))
    p = s["pitch"]
    print(_row("pitch", _num(p["filled"]),
               f"{p['not_found']} not in dict · {p['skipped']} no reading"))
    print(_row("sentence", _num(s["sentences"]["filled"]), "JRP markup"))
    print(_row("furigana", _num(s["furigana"]["fixed"]), "spacing"))
    e = s["enrich"]
    if "skipped" in e:
        print(_row("defs", _dim("    —"), f"skipped: {e['skipped']}"))
    elif apply:
        print(_row("defs", _num(e["written"]), f"{e['dropped']} dropped"))
    else:
        print(_row("defs", _num(e["to_write"]), f"{e['nohit']} no-hit"))
    f = s["frequency"]
    if "skipped" in f:
        print(_row("frequency", _dim("    —"), f"skipped: {f['skipped']}"))
    elif apply:
        print(_row("frequency", _num(f["written"]), f"{f['none']} no rank"))
    else:
        print(_row("frequency", _num(f["to_write"]), f"{f['none']} no rank"))
    x = s["examples"]
    if "skipped" in x:
        print(_row("examples", _dim("    —"), f"skipped: {x['skipped']}"))
    elif apply:
        print(_row("examples", _num(x["written"]), f"{x['none_tagged']} no-example"))
    else:
        print(_row("examples", _num(x["to_write"]), f"{x['none']} no-example"))
    c = s["cloze"]
    print(_row("cloze", _num(c["filled"]), f"{c['skipped']} unlocatable"))
    terms = e.get("terms") or []
    if terms:
        print()
        if len(terms) > _TERM_CAP:
            print(_row("new", _num(len(terms)), "terms (too many to list)"))
        else:
            print(_row("new", _bold(_cyan(str(len(terms)))), "、".join(terms)))
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


def _safe_notify(text: str) -> None:
    try:
        notify.send_message(text)
    except Exception as exc:  # noqa: BLE001 — never mask the underlying failure
        print(_red(f"telegram notify failed: {exc}"), file=sys.stderr)


def _run(args) -> int:
    col = collection.open_working()
    notify_on = getattr(args, "notify", False)
    phase, pull_action = "sync/login", None
    try:
        auth = sync.login(col)
        phase = "sync/pull"
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
            push_action = None
            if args.apply:
                phase = "sync/push"
                push_action, auth = sync.push(col, auth)
                _sync_line("push", push_action)
            _render(res, args.apply)
            if notify_on:
                # _safe_notify: a send failure must not flip the exit status of
                # an already-completed apply.
                _safe_notify(notify.format_report(
                    res, apply=args.apply, pull_action=pull_action,
                    push_action=push_action))
    except pipeline.PipelineError as exc:
        if notify_on:
            _safe_notify(notify.format_report(
                None, apply=args.apply, pull_action=pull_action,
                error=(exc.step, exc.original)))
        raise
    except Exception as exc:
        if notify_on:
            _safe_notify(notify.format_report(
                None, apply=getattr(args, "apply", False), pull_action=pull_action,
                error=(phase, exc)))
        raise
    finally:
        col.close()
    return 0


def main(argv=None) -> int:
    if argv is None:
        argv = sys.argv[1:]
    if wizard.should_run_wizard(argv, sys.stdin.isatty() and sys.stdout.isatty()):
        args = wizard.run(dry_run="--dry-run" in argv)
        if args is None:
            return 0
        args.notify = "--notify" in argv
    else:
        ap = argparse.ArgumentParser(prog="anki-enrich",
                                     description="Post-sync Anki enrichment.")
        ap.add_argument("command", nargs="?", default="run",
                        choices=["run", "dedupe", "template"])
        ap.add_argument("--apply", action="store_true", help="write + push (else dry run)")
        ap.add_argument("--overwrite", action="store_true",
                        help="re-assert pitch, sentences, defs, examples AND cloze on every card")
        ap.add_argument("--overwrite-pitch", action="store_true")
        ap.add_argument("--overwrite-sentences", action="store_true")
        ap.add_argument("--overwrite-defs", action="store_true")
        ap.add_argument("--overwrite-examples", action="store_true")
        ap.add_argument("--overwrite-cloze", action="store_true")
        ap.add_argument("--dry-run", action="store_true",
                        help="preview only (the default); bare `--dry-run` opens the wizard")
        ap.add_argument("--notify", action="store_true",
                        help="send the run result to Telegram (needs TELEGRAM_* env)")
        args = ap.parse_args(argv)

    if getattr(args, "apply", False):
        try:
            with runlock.acquire():
                return _run(args)
        except runlock.Busy:
            msg = "⏳ a run is already in progress, skipping"
            if getattr(args, "notify", False):
                notify.send_message(msg)
            else:
                print("\n  " + _dim(msg) + "\n")
            return 0
    return _run(args)


if __name__ == "__main__":
    raise SystemExit(main())
