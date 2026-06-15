# Interactive wizard for `anki-enrich`

## Goal

Replace the need to remember long flags with a friendly, gum-style interactive
wizard. Bare `anki-enrich` in a terminal becomes a guided, apply-first flow;
every existing flag still works unchanged for scripts and CI.

## Trigger

The wizard launches when **both**:

- stdin and stdout are a TTY, and
- `argv` is empty **or** exactly `["--dry-run"]`.

Every other invocation (any other flag, any subcommand, non-TTY/piped, or
`questionary` not importable) bypasses the wizard and runs today's behavior.

`--dry-run` is the one flag that *enters* the wizard rather than bypassing it —
it runs the wizard in preview-only mode.

In flag mode the CLI default stays **dry-run** (scripts/CI safe-by-default;
`--apply` still writes). The wizard, by contrast, is **apply-first**.

`--dry-run` is also registered as a real (no-op) argparse flag meaning "preview"
— which is already the flag-mode default. This keeps combinations like
`anki-enrich dedupe --dry-run` from erroring; only a *bare* `--dry-run` (sole
arg, TTY) enters the wizard.

## Flow

```
$ anki-enrich                 # apply-first wizard
$ anki-enrich --dry-run       # same wizard, preview-only

? What do you want to do?   (arrow keys)
  ▸ Enrich cards            (run)
    Find & merge duplicates (dedupe)
    Apply card template     (template)
    Quit

# Enrich only — re-assert step (shown in both modes; it changes the counts):
? Re-assert any fields? (space toggles, enter confirms)   [checkbox]
  ◯ pitch  ◯ sentences  ◯ defs  ◯ examples  ◯ cloze
  (none checked = fill-missing only, the normal case)

# apply mode only (skipped under --dry-run):
? Writes to your collection and pushes to AnkiWeb. Continue?  (Y/n)   # default Yes

→ anki-enrich --apply --overwrite-pitch        # echoes the equivalent flags
  …executes the existing code path…
```

- The re-assert checkbox maps to the `--overwrite-*` flags. All five checked is
  equivalent to `--overwrite`. Default is none checked (fill-missing only).
- `dedupe` and `template` have no re-assert step.
- The final confirm appears only in apply mode and defaults to **Yes** (the user
  navigated here intentionally). Declining exits 0 with no writes.
- Before executing, the wizard prints the equivalent command line so the flags
  are learnable over time.

## Architecture

New module `ankienrich/wizard.py`. `cli.main()` gains one branch at the top:
when the trigger fires, call the wizard to obtain an `argparse.Namespace`
matching what the flags would have produced, then fall through to the existing
execution code unchanged.

Two pure, unit-testable helpers carry the logic; the `questionary` prompt calls
are a thin shell around them:

- `should_run_wizard(argv, isatty) -> bool` — the trigger predicate.
- `_namespace_from_answers(action, overwrite_fields, dry_run) -> Namespace` —
  maps collected answers to the same `Namespace` argparse yields (`command`,
  `apply`, `overwrite`, `overwrite_pitch`, …). `dry_run=True` → `apply=False`;
  otherwise `apply=True`.

### Dependency

`questionary` goes in a new optional `wizard` dependency group in
`pyproject.toml`, added to `tool.uv.default-groups` so `uv run anki-enrich`
picks it up. The package still installs without it.

## Error handling & degradation

- `questionary` not importable → print a one-line hint and fall back to the
  normal default run (dry-run). Never crash.
- Ctrl-C / Esc at any prompt → clean exit, no writes.
- "Quit" choice → exit 0, no-op.
- Non-TTY with empty argv (piped) → today's default behavior, no wizard.

## Testing

- `should_run_wizard` — table of argv/isatty combinations.
- `_namespace_from_answers` — each action, overwrite-field combinations, and the
  dry-run vs apply mapping; asserts the resulting `Namespace` equals what
  argparse produces for the equivalent flags.
- The `questionary` prompt shell is not unit-tested.

## Out of scope (YAGNI)

- Chaining "apply now?" after a dry run (would re-run the pipeline twice).
- Shelling out to the real `gum` binary.
- A persistent full-screen TUI.
