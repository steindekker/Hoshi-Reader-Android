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


def test_namespace_run_apply_no_overwrite():
    ns = wizard._namespace_from_answers("run", set(), dry_run=False)
    assert ns.command == "run"
    assert ns.apply is True
    assert ns.overwrite is False
    assert ns.overwrite_cloze is False
    assert not hasattr(ns, "overwrite_pitch")  # pitch is always re-asserted


def test_namespace_dry_run_sets_apply_false():
    ns = wizard._namespace_from_answers("run", set(), dry_run=True)
    assert ns.apply is False


def test_namespace_overwrite_fields_fan_in():
    ns = wizard._namespace_from_answers("run", {"fields", "cloze"}, dry_run=False)
    assert ns.overwrite_fields is True
    assert ns.overwrite_cloze is True
    assert ns.overwrite_sentences is False
    assert ns.overwrite is False  # per-field flags carry it, not the global


def test_namespace_dedupe_has_all_fields():
    ns = wizard._namespace_from_answers("dedupe", set(), dry_run=False)
    assert ns.command == "dedupe"
    assert ns.apply is True
    assert ns.overwrite_fields is False
