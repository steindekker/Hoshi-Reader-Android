from ankienrich import bot


def test_authorized():
    assert bot._authorized(42, 42)
    assert not bot._authorized(99, 42)


# --- argv_for: maps a set of selected fields to the spawn command -----------

def test_argv_for_empty_is_fill_missing():
    assert bot.argv_for(set()) == ["anki-enrich", "--apply", "--notify"]


def test_argv_for_single_field():
    assert bot.argv_for({"fields"}) == [
        "anki-enrich", "--apply", "--overwrite-fields", "--notify"]


def test_argv_for_sentence_uses_plural_flag():
    # field name is "sentence" but the CLI flag is --overwrite-sentences
    assert bot.argv_for({"sentence"}) == [
        "anki-enrich", "--apply", "--overwrite-sentences", "--notify"]


def test_argv_for_orders_flags_canonically_regardless_of_set_order():
    assert bot.argv_for({"cloze", "sentence"}) == [
        "anki-enrich", "--apply",
        "--overwrite-sentences", "--overwrite-cloze", "--notify"]


def test_argv_for_all_fields():
    assert bot.argv_for(set(bot.OVERWRITE_FIELDS)) == [
        "anki-enrich", "--apply",
        "--overwrite-sentences", "--overwrite-fields", "--overwrite-cloze", "--notify"]


# --- toggled / select_all_toggle: pure set transitions ----------------------

def test_toggled_adds_when_absent():
    assert bot.toggled(set(), "fields") == {"fields"}


def test_toggled_removes_when_present():
    assert bot.toggled({"cloze", "fields"}, "cloze") == {"fields"}


def test_select_all_toggle_selects_all_from_partial():
    assert bot.select_all_toggle({"fields"}) == set(bot.OVERWRITE_FIELDS)


def test_select_all_toggle_clears_when_all_selected():
    assert bot.select_all_toggle(set(bot.OVERWRITE_FIELDS)) == set()


# --- keyboard_for: reflects selection, exposes the right callbacks ----------

def test_keyboard_for_marks_selected_fields():
    kb = bot.keyboard_for({"fields"})
    labels = {b.text: b.callback_data for row in kb.keyboard for b in row}
    assert labels[f"✅ {bot.FIELD_LABELS['fields']}"] == "toggle:fields"
    assert labels[f"⬜ {bot.FIELD_LABELS['cloze']}"] == "toggle:cloze"


def test_keyboard_for_has_all_apply_cancel_and_one_toggle_per_field():
    kb = bot.keyboard_for(set())
    datas = [b.callback_data for row in kb.keyboard for b in row]
    assert "all" in datas
    assert "apply" in datas
    assert "cancel" in datas
    assert sum(d.startswith("toggle:") for d in datas) == len(bot.OVERWRITE_FIELDS)


def test_keyboard_all_button_label_flips_when_all_selected():
    partial = [b.text for row in bot.keyboard_for({"fields"}).keyboard for b in row]
    full = [b.text for row in bot.keyboard_for(set(bot.OVERWRITE_FIELDS)).keyboard for b in row]
    assert "Overwrite all" in partial
    assert "Clear all" in full


# --- home_keyboard: persistent reply keyboard so no command needs typing -----

def test_home_keyboard_offers_run_and_help_buttons():
    kb = bot.home_keyboard()
    labels = [b["text"] for row in kb.keyboard for b in row]
    assert bot.RUN_BUTTON in labels
    assert bot.HELP_BUTTON in labels


def test_home_keyboard_is_persistent_and_resized():
    kb = bot.home_keyboard()
    assert kb.resize_keyboard
    assert kb.is_persistent


# --- confirmation_text ------------------------------------------------------

def test_confirmation_text_empty():
    assert bot.confirmation_text(set()) == "▶️ Started (fill missing only)"


def test_confirmation_text_lists_fields_in_canonical_order():
    assert bot.confirmation_text({"cloze", "sentence"}) == \
        "▶️ Started — overwrite: Sentence, Cloze"
