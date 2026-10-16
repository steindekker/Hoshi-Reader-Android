"""anki-enrich add: create a Kinomoto note, then fill-missing enrich that note.

Dry-run by default. --apply: pull -> add (dedup) -> enrich that note only -> push.
"""
from __future__ import annotations

import argparse
import os
import sys
from typing import Callable

from . import addnotes, collection, config, pipeline, runlock, sync
from .addnotes import NoteSpec


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="anki-enrich add",
        description=(
            "Create a Hoshi note, then fill-missing enrich that note. "
            "Dry-run by default; --apply pulls, adds, enriches the new note only, "
            "and pushes once."
        ),
    )
    p.add_argument("term", help="Term field (Japanese headword)")
    p.add_argument("--reading", default=None, help="Reading (kana)")
    p.add_argument("--sentence", default=None, help="Example sentence")
    p.add_argument("--furigana", default=None, help="Furigana markup for Term")
    p.add_argument(
        "--image",
        default=None,
        metavar="PATH",
        help="Absolute path to an image file; copied into collection media on --apply",
    )
    p.add_argument(
        "--deck",
        default=config.DEFAULT_DECK,
        help=f"Target deck (default: {config.DEFAULT_DECK})",
    )
    p.add_argument(
        "--apply",
        action="store_true",
        help="Pull AnkiWeb, add note, enrich that note, push (needs credentials)",
    )
    return p


def validate_image_path(path: str) -> str:
    if not os.path.isabs(path):
        raise ValueError(f"--image must be an absolute path (got {path!r})")
    if not os.path.isfile(path):
        raise ValueError(f"--image file not found: {path}")
    return path


def _preview(spec: NoteSpec, dup_ids: list[int]) -> None:
    values = addnotes.field_map(spec)
    print(f"notetype: {config.NOTETYPE}")
    print(f"deck:     {spec.deck}")
    print("fields:")
    for name in (
        config.TERM,
        config.READING,
        config.TERM_FURIGANA,
        config.SENTENCE,
    ):
        print(f"  {name}: {values[name]!r}")
    if spec.image_path:
        planned = addnotes.planned_media_name(spec.image_path)
        print(f"  {config.IMAGE}: {values[config.IMAGE]!r}")
        print(f"image:    {spec.image_path}")
        print(f"media:    planned filename {planned!r}")
    else:
        print(f"  {config.IMAGE}: (none)")
    print("  (enrich fields left empty until fill-missing on --apply)")
    if dup_ids:
        print(f"dedup:    DUPLICATE — {len(dup_ids)} existing note(s): {dup_ids}")
    else:
        print("dedup:    ok (no matching Term/Reading)")


def _apply_body(col, spec: NoteSpec, ops: dict, enrich_fn: Callable) -> int:
    dup_ids = addnotes.find_duplicates(col, spec)
    if dup_ids:
        print(
            "error: refusing to add duplicate Term"
            + ("+Reading" if spec.reading else "")
            + f" — existing note id(s): {dup_ids}",
            file=sys.stderr,
        )
        return 2

    print("mode:     apply")
    auth = ops["login"](col)
    pull_action, auth = ops["pull"](col, auth)
    print(f"sync:     pull → {pull_action}")

    dup_ids = addnotes.find_duplicates(col, spec)
    if dup_ids:
        print(
            f"error: duplicate found after pull — note id(s): {dup_ids}",
            file=sys.stderr,
        )
        return 2

    image_fname: str | None = None
    if spec.image_path:
        image_fname = addnotes.add_media(col, spec.image_path)
        print(f"media:    added {image_fname!r}")

    nid = addnotes.add_note(col, spec, image_fname=image_fname)
    print(f"added:    note id {nid}")

    print(f"enrich:   fill-missing for note {nid} only")
    enrich_res = enrich_fn(col, note_ids=[nid])
    print(f"enrich:   scanned {enrich_res.get('scanned', '?')}")

    push_action, auth = ops["push"](col, auth)
    print(f"sync:     push → {push_action}")
    return 0


def run(
    spec: NoteSpec,
    *,
    apply: bool,
    open_col: Callable | None = None,
    sync_ops: dict | None = None,
    acquire_lock: Callable | None = None,
    lock_held: Callable | None = None,
    enrich_fn: Callable | None = None,
) -> int:
    """Core entry used by CLI and tests.

    enrich_fn(col, note_ids=...) defaults to pipeline.run(..., apply=True).
    sync_ops may inject login/pull/push for fixture tests.
    """
    open_col = open_col or collection.open_working
    acquire_lock = acquire_lock or runlock.acquire
    lock_held = lock_held or runlock.is_held

    def _default_enrich(col, *, note_ids):
        return pipeline.run(col, apply=True, note_ids=note_ids)

    enrich_fn = enrich_fn or _default_enrich

    col = open_col()
    try:
        addnotes.ensure_notetype(col)
        dup_ids = addnotes.find_duplicates(col, spec)
        _preview(spec, dup_ids)

        if not apply:
            held = lock_held()
            print(
                "lock:     held by another process"
                if held
                else "lock:     free"
            )
            print("mode:     dry-run (no write/push)")
            print("would:    pull → add → enrich-that-note → push")
            return 0

        ops = sync_ops or {
            "login": sync.login,
            "pull": sync.pull,
            "push": sync.push,
        }

        try:
            with acquire_lock():
                return _apply_body(col, spec, ops, enrich_fn)
        except runlock.Busy:
            print(
                "error: another enrich/add holds run.lock — refusing --apply. "
                "Retry when the other run finishes.",
                file=sys.stderr,
            )
            return 3
    finally:
        col.close()


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    image_path = None
    if args.image is not None:
        try:
            image_path = validate_image_path(args.image)
        except ValueError as e:
            print(f"error: {e}", file=sys.stderr)
            return 1
    spec = NoteSpec(
        term=args.term,
        reading=args.reading,
        furigana=args.furigana,
        sentence=args.sentence,
        deck=args.deck,
        image_path=image_path,
    )
    return run(spec, apply=args.apply)
