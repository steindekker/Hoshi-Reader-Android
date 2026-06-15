"""Interactive apply-first wizard for `anki-enrich`.

Launches on bare invocation (or sole `--dry-run`) in a TTY; every other
invocation bypasses it and runs the flag-based path unchanged.
"""
import argparse


_FIELDS = ("pitch", "sentences", "defs", "examples", "cloze")


def should_run_wizard(argv: list[str], isatty: bool) -> bool:
    """True when argv is empty or exactly ['--dry-run'], and we're on a TTY."""
    return isatty and argv in ([], ["--dry-run"])


def _namespace_from_answers(action: str, overwrite_fields: set[str],
                            dry_run: bool) -> argparse.Namespace:
    """Build the same Namespace argparse would yield for the equivalent flags."""
    return argparse.Namespace(
        command=action,
        apply=not dry_run,
        overwrite=False,
        **{f"overwrite_{f}": (f in overwrite_fields) for f in _FIELDS},
    )
