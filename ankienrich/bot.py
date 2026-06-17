"""Telegram command listener. Thin: /run shells out to
`anki-enrich --apply [--overwrite] --notify`, which sends the report itself —
the bot never blocks on a run and never re-implements the pipeline."""
from __future__ import annotations

import os
import subprocess

import telebot


def _spawn_argv(*, overwrite: bool) -> list[str]:
    argv = ["anki-enrich", "--apply", "--notify"]
    if overwrite:
        argv.insert(2, "--overwrite")
    return argv


def _authorized(chat_id: int, expected: int) -> bool:
    return chat_id == expected


def _run_keyboard() -> telebot.types.InlineKeyboardMarkup:
    kb = telebot.types.InlineKeyboardMarkup()
    kb.add(telebot.types.InlineKeyboardButton("Apply", callback_data="run:apply"),
           telebot.types.InlineKeyboardButton("Apply + overwrite", callback_data="run:overwrite"))
    kb.add(telebot.types.InlineKeyboardButton("Cancel", callback_data="run:cancel"))
    return kb


def _dispatch(data: str) -> str | None:
    """Act on a callback. Returns the confirmation label, or None for cancel."""
    action = data.split(":", 1)[1]
    if action == "cancel":
        return None
    subprocess.Popen(_spawn_argv(overwrite=action == "overwrite"),
                     start_new_session=True,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return ("▶️ Run started (apply + overwrite)" if action == "overwrite"
            else "▶️ Run started")


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
        bot.reply_to(m, "Commands:\n/run — run enrichment now")

    @bot.message_handler(commands=["run"])
    def _run(m):
        if not _authorized(m.chat.id, chat_id):
            return
        bot.reply_to(m, "Run enrichment now?", reply_markup=_run_keyboard())

    @bot.callback_query_handler(func=lambda c: True)
    def _cb(c):
        if not _authorized(c.message.chat.id, chat_id):
            return
        label = _dispatch(c.data)
        bot.answer_callback_query(c.id, "Cancelled" if label is None else "Started")
        bot.edit_message_text(label or "🚫 Cancelled",
                              c.message.chat.id, c.message.message_id)

    return bot


def main(argv=None) -> int:
    token, chat_id = _config()
    build_bot(token, chat_id).infinity_polling()
    return 0
