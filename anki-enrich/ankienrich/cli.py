"""Console entry point: `anki-enrich`.

  uv run anki-enrich            # pull latest, dry-run preview of the suite
  uv run anki-enrich --apply    # pull, backup, write, push to AnkiWeb
  uv run anki-enrich --apply --overwrite   # re-assert sentences/fields/cloze on every card
  uv run anki-enrich dedupe --apply         # destructive duplicate merge (separate)
  uv run anki-enrich template --apply       # apply repo card design to note type
  uv run anki-enrich add TERM [--reading ...]   # dry-run create+enrich preview
  uv run anki-enrich add TERM --apply           # pull, add, enrich that note, push
  uv run anki-enrich pack                   # build the Hoshi enrichment pack (offline)
"""
import argparse
import sys

from . import addcli, collection, dedupe as _dedupe, notify, pipeline, runlock, sync, template, wizard

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


def _sync_line(direction: str, action: str) -> None:
    if action == "none":            # no transfer — the always-the-same line, skip it
        return
    text = notify.SYNC_TEXT.get(direction, {}).get(action, action)
    print(_row("sync", _cyan(f"{direction} — {text}")))


def _render(res: dict, apply: bool) -> None:
    steps = res["steps"]
    print()
    print(_row("scanned", f"{_bold(res['scanned'])} cards"))
    if res["backup"]:
        print(_row("backup", _dim(res["backup"])))
    print()
    audio = steps["audio"]
    if "skipped" in audio:
        print(_row("audio", _dim("    —"), f"skipped: {audio['skipped']}"))
    elif apply:
        filled = sum(audio["filled"].values())
        detail = " · ".join(part for part in (
            f"{audio['unchanged']} already store audio" if audio.get("unchanged") else "",
            f"{audio['errors']} errors" if audio["errors"] else "") if part)
        print(_row("audio", _num(filled), detail))
    else:
        none = _red(f"{audio['none']} no match") if audio["none"] else _dim(f"{audio['none']} no match")
        print(_row("audio", _num(audio["will_fill"]), none))
    loud = steps["loudness"]
    if "skipped" in loud:
        print(_row("loudness", _dim("    —"), f"skipped: {loud['skipped']}"))
    elif apply:
        print(_row("loudness", _num(loud["normalized"]),
                   f"{loud['median_gain']:+.1f} dB median · {loud['unchanged']} already level"
                   + (f" · {loud['errors']} errors" if loud["errors"] else "")))
    else:
        print(_row("loudness", _num(loud["to_normalize"]),
                   f"clips on {loud['notes']} cards"))
    print(_row("sentence", _num(steps["sentences"]["filled"]), "JRP markup"))
    print(_row("furigana", _num(steps["furigana"]["fixed"]), "spacing"))
    fields = steps["fields"]
    if "skipped" in fields:
        print(_row("fields", _dim("    —"), f"skipped: {fields['skipped']}"))
    else:
        nohit = (f"{fields['dropped']} dropped" if fields["dropped"]
                 else f"{fields['nohit']} no-hit")
        print(_row("defs", _num(fields["defs"]), nohit))
        print(_row("examples", _num(fields["examples"]), f"{fields['no_example']} no-example"))
        print(_row("pitch", _num(fields["pitch"]), f"{fields['pitch_cleared']} cleared"))
        print(_row("frequency", _num(fields["frequency"])))
    cloze = steps["cloze"]
    detail = " · ".join(part for part in (
        f"{cloze['resynced']} resynced" if cloze.get("resynced") else "",
        f"{cloze['skipped']} unlocatable",
        f"{cloze['mismatched']} flagged" if cloze.get("mismatched") else "") if part)
    print(_row("cloze", _num(cloze["filled"]), detail))
    terms = steps["fields"].get("terms") or []
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
                # not folded into --overwrite: see the flag's definition
                overwrite_audio=getattr(args, "overwrite_audio", False),
                overwrite_sentences=args.overwrite_sentences or args.overwrite,
                overwrite_fields=args.overwrite_fields or args.overwrite,
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


def _pack(argv) -> int:
    """`anki-enrich pack [--out DIR]`: build the Hoshi enrichment pack. Offline —
    reads the local indexes only, never the collection or AnkiWeb."""
    from . import pack
    ap = argparse.ArgumentParser(prog="anki-enrich pack",
                                 description="Build the Hoshi enrichment pack.")
    ap.add_argument("--out", help="output dir (default var/pack)")
    args = ap.parse_args(argv)

    def progress(done, total):
        print(f"\r  {_dim('building')} {done}/{total}", end="", flush=True)

    manifest = pack.build(args.out, progress=progress if _TTY else None)
    print("\r" + _row("pack", _green(manifest["version"]),
                      f"{manifest['entries']} entries · {manifest['size'] / 1e6:.0f} MB") + " " * 10)
    sentence = manifest.get("sentence")
    if sentence:
        acc, dic = sentence["accent"], sentence["dictionary"]
        print(_row("sentence", _green(acc["version"]),
                   f"accent {acc['size'] / 1e6:.0f} MB · {dic['source']} {dic['size'] / 1e6:.0f} MB"))
    else:
        print(_row("sentence", _dim("skipped"), "sudachidict_full not installed"))
    return 0


def main(argv=None) -> int:
    if argv is None:
        argv = sys.argv[1:]
    # Subcommand with its own argv shape (TERM + flags) — dispatch before wizard/parser.
    if argv and argv[0] == "add":
        return addcli.main(argv[1:])
    if argv[:1] == ["pack"]:
        return _pack(argv[1:])
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
                        help="re-assert sentences, fields AND cloze on every card "
                             "(pitch + frequency are always re-asserted; audio is NOT — "
                             "see --overwrite-audio)")
        # Deliberately outside --overwrite: this replaces mined audio on every
        # card, re-encodes each new clip, and re-uploads the media to AnkiWeb.
        # Too expensive and too destructive to ride along on a blanket flag.
        ap.add_argument("--overwrite-audio", action="store_true",
                        help="re-assert store audio over EVERY card, replacing mined clips "
                             "(cards the store cannot serve keep their audio)")
        ap.add_argument("--overwrite-sentences", action="store_true")
        ap.add_argument("--overwrite-fields", action="store_true",
                        help="re-assert definition + examples from the pack on every card "
                             "(e.g. after switching a dictionary source)")
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
