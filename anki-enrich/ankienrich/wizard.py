"""Interactive apply-first wizard for `anki-enrich`.

Launches on bare invocation (or sole `--dry-run`) in a TTY; every other
invocation bypasses it and runs the flag-based path unchanged.
"""
import argparse


# "fields" = definition + examples from the pack; pitch and frequency are always
# re-asserted (no per-run toggle), so they're not offered here.
_FIELDS = ("sentences", "fields", "cloze")


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


_ACTIONS = [
    ("Enrich cards", "run"),
    ("Find & merge duplicates", "dedupe"),
    ("Apply card template", "template"),
    ("Quit", "quit"),
]


def _echo_command(ns: argparse.Namespace) -> None:
    """Print the equivalent flag invocation, so the flags are learnable."""
    parts = ["anki-enrich"]
    if ns.command != "run":
        parts.append(ns.command)
    if ns.apply:
        parts.append("--apply")
    for f in _FIELDS:
        if getattr(ns, f"overwrite_{f}"):
            parts.append(f"--overwrite-{f}")
    print(f"\n  → {' '.join(parts)}\n")


def run(dry_run: bool) -> argparse.Namespace | None:
    """Interactively build a Namespace. Returns None to quit/cancel."""
    try:
        import questionary
    except ImportError:
        print("  (install the `wizard` extra for the interactive menu; "
              "running a dry-run preview)")
        return _namespace_from_answers("run", set(), dry_run=True)

    action = questionary.select(
        "What do you want to do?",
        choices=[questionary.Choice(label, value) for label, value in _ACTIONS],
    ).ask()
    if action in (None, "quit"):
        return None

    fields: set[str] = set()
    if action == "run":
        picked = questionary.checkbox(
            "Re-assert any fields? (space toggles, enter confirms)",
            choices=list(_FIELDS),
        ).ask()
        if picked is None:  # Ctrl-C
            return None
        fields = set(picked)

    if not dry_run:
        ok = questionary.confirm(
            "Writes to your collection and pushes to AnkiWeb. Continue?",
            default=True,
        ).ask()
        if not ok:
            return None

    ns = _namespace_from_answers(action, fields, dry_run)
    _echo_command(ns)
    return ns
