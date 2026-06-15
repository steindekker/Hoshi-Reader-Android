"""Interactive apply-first wizard for `anki-enrich`.

Launches on bare invocation (or sole `--dry-run`) in a TTY; every other
invocation bypasses it and runs the flag-based path unchanged.
"""


def should_run_wizard(argv: list[str], isatty: bool) -> bool:
    """True when argv is empty or exactly ['--dry-run'], and we're on a TTY."""
    return isatty and argv in ([], ["--dry-run"])
