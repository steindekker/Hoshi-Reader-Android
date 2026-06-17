"""Telegram command listener. Thin: /run shows a toggle keyboard to pick which
fields to overwrite, then Apply shells out to
`anki-enrich --apply [--overwrite-*...] --notify`, which sends the report itself
— the bot never blocks on a run and never re-implements the pipeline."""
from __future__ import annotations

import os
import subprocess

import telebot

# Fields the pipeline can re-assert (overwrite), in canonical display/flag order.
# Audio has no overwrite flag, so it is not offered here. Note "sentence" maps to
# the plural CLI flag.
OVERWRITE_FIELDS = ["pitch", "sentence", "defs", "examples", "cloze"]
OVERWRITE_FLAGS = {
    "pitch": "--overwrite-pitch",
    "sentence": "--overwrite-sentences",
    "defs": "--overwrite-defs",
    "examples": "--overwrite-examples",
    "cloze": "--overwrite-cloze",
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
        return "▶️ Run started (fill missing)"
    ordered = [f for f in OVERWRITE_FIELDS if f in fields]
    return "▶️ Run started — overwriting: " + ", ".join(ordered)


def keyboard_for(fields: set[str]) -> telebot.types.InlineKeyboardMarkup:
    btn = telebot.types.InlineKeyboardButton
    kb = telebot.types.InlineKeyboardMarkup()

    def field_btn(name: str):
        mark = "✅" if name in fields else "⬜"
        return btn(f"{mark} {name.title()}", callback_data=f"toggle:{name}")

    kb.add(field_btn("pitch"), field_btn("sentence"))
    kb.add(field_btn("defs"), field_btn("examples"))
    kb.add(field_btn("cloze"))
    all_label = "Clear all" if fields == set(OVERWRITE_FIELDS) else "Overwrite all"
    kb.add(btn(all_label, callback_data="all"))
    kb.add(btn("▶️ Apply", callback_data="apply"),
           btn("Cancel", callback_data="cancel"))
    return kb


def _authorized(chat_id: int, expected: int) -> bool:
    return chat_id == expected


def _spawn(fields: set[str]) -> None:
    subprocess.Popen(argv_for(fields), start_new_session=True,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def _config() -> tuple[str, int]:
    token = os.environ.get("TELEGRAM_BOT_TOKEN")
    chat = os.environ.get("TELEGRAM_CHAT_ID")
    if not token or not chat:
        raise RuntimeError(
            "set TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID in the environment")
    return token, int(chat)


def build_bot(token: str, chat_id: int) -> telebot.TeleBot:
    bot = telebot.TeleBot(token, parse_mode="HTML")

    @bot.message_handler(commands=["start", "help"])
    def _help(m):
        if not _authorized(m.chat.id, chat_id):
            return
        bot.reply_to(m, "Commands:\n/run — pick fields to overwrite, then Apply")

    @bot.message_handler(commands=["run"])
    def _run(m):
        if not _authorized(m.chat.id, chat_id):
            return
        sent = bot.reply_to(
            m, "Run enrichment now? Tap fields to overwrite, then Apply.",
            reply_markup=keyboard_for(set()))
        _SELECTED[sent.message_id] = set()

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
    build_bot(token, chat_id).infinity_polling()
    return 0
