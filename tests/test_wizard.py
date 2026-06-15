from ankienrich import wizard


def test_trigger_fires_on_empty_argv_in_tty():
    assert wizard.should_run_wizard([], isatty=True) is True


def test_trigger_fires_on_sole_dry_run_in_tty():
    assert wizard.should_run_wizard(["--dry-run"], isatty=True) is True


def test_trigger_skips_without_tty():
    assert wizard.should_run_wizard([], isatty=False) is False
    assert wizard.should_run_wizard(["--dry-run"], isatty=False) is False


def test_trigger_skips_on_any_other_argv():
    assert wizard.should_run_wizard(["--apply"], isatty=True) is False
    assert wizard.should_run_wizard(["dedupe"], isatty=True) is False
    assert wizard.should_run_wizard(["--dry-run", "--apply"], isatty=True) is False
