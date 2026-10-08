"""Telegram command listener. Thin: a persistent ▶️ Run button (or /run) shows a
toggle keyboard to pick which fields to overwrite, then Apply shells out to
`anki-enrich --apply [--overwrite-*...] --notify`, which sends the report itself
— the bot never blocks on a run and never re-implements the pipeline."""
from __future__ import annotations

import subprocess

import telebot

from . import config

# Persistent reply-keyboard buttons, so a run is one tap and never a typed command.
RUN_BUTTON = "▶️ Run"
HELP_BUTTON = "❓ Help"

HELP_TEXT = "▶️ Run → pick the fields to overwrite, then Apply."

# Fields the pipeline can re-assert (overwrite), in canonical display/flag order.
# "fields" is definition + examples from the pack; pitch and frequency are always
# re-asserted, so they aren't offered. Audio is deliberately absent
# too: --overwrite-audio replaces mined clips on every card, re-encodes them and
# re-uploads the media — not something a stray phone tap should start. It stays
# CLI-only. Note "sentence" maps to the plural CLI flag. The keys are internal
# (callback data + CLI flags); display labels live in FIELD_LABELS.
OVERWRITE_FIELDS = ["sentence", "fields", "cloze"]
OVERWRITE_FLAGS = {
    "sentence": "--overwrite-sentences",
    "fields": "--overwrite-fields",
    "cloze": "--overwrite-cloze",
}
FIELD_LABELS = {
    "sentence": "Sentence",
    "fields": "Defs + examples",
    "cloze": "Cloze",
}

# Live /run selections, keyed by the keyboard's message id. In-memory: a restart
# drops them, and a stale tap just reports "expired" so the user sends /run again.
_SELECTED: dict[int, set[str]] = {}


def argv_for(fields: set[str]) -> list[str]:
    """Spawn command for the given overwrite selection (empty = fill missing)."""
    flags = [OVERWRITE_FLAGS[f] for f in OVERWRITE_FIELDS if f in fields]
    return ["anki-enrich", "--apply", *flags, "--notify"]


def toggled(fields: set[str], name: str) -> set[str]:
    """`fields` with `name` flipped on/off."""
    return fields ^ {name}


def select_all_toggle(fields: set[str]) -> set[str]:
    """All fields, or empty when they were already all selected."""
    everything = set(OVERWRITE_FIELDS)
    return set() if fields == everything else everything


def confirmation_text(fields: set[str]) -> str:
    if not fields:
        return "▶️ Started (fill missing only)"
    ordered = [FIELD_LABELS[f] for f in OVERWRITE_FIELDS if f in fields]
    return "▶️ Started — overwrite: " + ", ".join(ordered)


def keyboard_for(fields: set[str]) -> telebot.types.InlineKeyboardMarkup:
    btn = telebot.types.InlineKeyboardButton
    kb = telebot.types.InlineKeyboardMarkup()

    def field_btn(name: str):
        mark = "✅" if name in fields else "⬜"
        return btn(f"{mark} {FIELD_LABELS[name]}", callback_data=f"toggle:{name}")

    for row in range(0, len(OVERWRITE_FIELDS), 2):  # two fields per keyboard row
        kb.add(*(field_btn(f) for f in OVERWRITE_FIELDS[row:row + 2]))
    all_label = "Clear all" if fields == set(OVERWRITE_FIELDS) else "Overwrite all"
    kb.add(btn(all_label, callback_data="all"))
    kb.add(btn("▶️ Apply", callback_data="apply"),
           btn("Cancel", callback_data="cancel"))
    return kb


def home_keyboard() -> telebot.types.ReplyKeyboardMarkup:
    """Persistent reply keyboard sitting above the input — tap instead of typing."""
    kb = telebot.types.ReplyKeyboardMarkup(resize_keyboard=True, is_persistent=True)
    kb.add(telebot.types.KeyboardButton(RUN_BUTTON),
           telebot.types.KeyboardButton(HELP_BUTTON))
    return kb


def _authorized(chat_id: int, expected: int) -> bool:
    return chat_id == expected


def _spawn(fields: set[str]) -> None:
    subprocess.Popen(argv_for(fields), start_new_session=True,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def _config() -> tuple[str, int]:
    token, chat = config.telegram_credentials()
    return token, int(chat)


def build_bot(token: str, chat_id: int) -> telebot.TeleBot:
    # threaded=False: single-user bot; nothing here is shared across worker threads.
    bot = telebot.TeleBot(token, parse_mode="HTML", threaded=False)

    def _show_home(m):
        bot.send_message(m.chat.id, HELP_TEXT, reply_markup=home_keyboard())

    def _open_run_menu(m):
        sent = bot.reply_to(
            m, "Pick the fields to overwrite, then Apply.",
            reply_markup=keyboard_for(set()))
        _SELECTED[sent.message_id] = set()

    @bot.message_handler(commands=["start", "help"])
    def _help(m):
        if not _authorized(m.chat.id, chat_id):
            return
        _show_home(m)

    @bot.message_handler(commands=["run"])
    def _run(m):
        if not _authorized(m.chat.id, chat_id):
            return
        _open_run_menu(m)

    @bot.message_handler(func=lambda m: m.text == RUN_BUTTON)
    def _run_button(m):
        if not _authorized(m.chat.id, chat_id):
            return
        _open_run_menu(m)

    @bot.message_handler(func=lambda m: m.text == HELP_BUTTON)
    def _help_button(m):
        if not _authorized(m.chat.id, chat_id):
            return
        _show_home(m)

    @bot.callback_query_handler(func=lambda c: True)
    def _cb(c):
        if not _authorized(c.message.chat.id, chat_id):
            return
        mid = c.message.message_id

        if c.data == "cancel":
            _SELECTED.pop(mid, None)
            bot.answer_callback_query(c.id, "Cancelled")
            bot.edit_message_text("🚫 Cancelled", c.message.chat.id, mid)
            return

        if mid not in _SELECTED:
            bot.answer_callback_query(c.id, "Menu expired — send /run again")
            return

        if c.data == "apply":
            fields = _SELECTED.pop(mid)
            _spawn(fields)
            bot.answer_callback_query(c.id, "Started")
            bot.edit_message_text(confirmation_text(fields), c.message.chat.id, mid)
            return

        if c.data == "all":
            _SELECTED[mid] = select_all_toggle(_SELECTED[mid])
        elif c.data.startswith("toggle:"):
            _SELECTED[mid] = toggled(_SELECTED[mid], c.data.split(":", 1)[1])

        bot.answer_callback_query(c.id)
        bot.edit_message_reply_markup(c.message.chat.id, mid,
                                      reply_markup=keyboard_for(_SELECTED[mid]))

    return bot


def main(argv=None) -> int:
    token, chat_id = _config()
    bot = build_bot(token, chat_id)
    bot.infinity_polling()
    return 0
