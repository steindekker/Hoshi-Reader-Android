"""Telegram command listener. Thin: a persistent ▶️ Run button (or /run) shows a
toggle keyboard to pick which fields to overwrite, then Apply shells out to
`anki-enrich --apply [--overwrite-*...] --notify`, which sends the report itself
— the bot never blocks on a run and never re-implements the pipeline."""
from __future__ import annotations

import os
import subprocess

import telebot

from . import enrich_one, mine, runlock
from .audio import index as audio_index
from .defs import index as defs_index
from .examples import index as ex_index
from .freq import build as freq_build, index as freq_index
from .jrp_fill import make_generator
from .jrp_fill.accent import AccentIndex

# Persistent reply-keyboard buttons, so a run is one tap and never a typed command.
RUN_BUTTON = "▶️ Run"
HELP_BUTTON = "❓ Help"

HELP_TEXT = ("Tap ▶️ Run to pick fields to overwrite, then Apply.\n"
             "Or just send a word to add a card "
             "(preview → sentence → image → add+sync).")

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
    token = os.environ.get("TELEGRAM_BOT_TOKEN")
    chat = os.environ.get("TELEGRAM_CHAT_ID")
    if not token or not chat:
        raise RuntimeError(
            "set TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID in the environment")
    return token, int(chat)


# --- Single-word mining: in-memory per-chat sessions + lazily-opened indexes ---
_SESSIONS: dict[int, mine.Session] = {}


def _inline(rows):
    """Inline keyboard, one button per (label, callback_data) row."""
    kb = telebot.types.InlineKeyboardMarkup()
    for label, payload in rows:
        kb.add(telebot.types.InlineKeyboardButton(label, callback_data=payload))
    return kb


def _indexes():
    """(defs_conn, ex_conn, audio_conn, acc, generate, freq_conn), opened once
    (read-only). freq_conn is None when no JPDB source was available to build it."""
    if not hasattr(_indexes, "_cache"):
        freq_conn = None
        if freq_build.ensure() != "no-source":
            freq_conn = freq_index.open_index()
        _indexes._cache = (defs_index.open_index(), ex_index.open_index(),
                           audio_index.open_index(), AccentIndex.load(),
                           make_generator(), freq_conn)
    return _indexes._cache


def build_bot(token: str, chat_id: int) -> telebot.TeleBot:
    # threaded=False: single-user bot, and the read-only sqlite indexes opened
    # lazily in _indexes() are not safe to share across telebot's worker threads.
    bot = telebot.TeleBot(token, parse_mode="HTML", threaded=False)

    def _show_home(m):
        bot.send_message(m.chat.id, HELP_TEXT, reply_markup=home_keyboard())

    def _open_run_menu(m):
        sent = bot.reply_to(
            m, "Run enrichment now? Tap fields to overwrite, then Apply.",
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

    # --- Mining flow: free-text word → preview → sentence → image → add+sync ----

    def _enter_preview(chat_id_, cand_idx):
        s = _SESSIONS[chat_id_]
        reading = s.candidates[cand_idx].reading
        defs_conn, ex_conn, audio_conn, acc, _gen, freq_conn = _indexes()
        s.fields = enrich_one.build_fields(
            s.term, reading, defs_conn=defs_conn, ex_conn=ex_conn,
            audio_conn=audio_conn, acc=acc, freq_conn=freq_conn)
        s.state = "preview"
        if s.fields.audio_clip:
            try:
                bot.send_voice(chat_id_, s.fields.audio_clip)
            except Exception:
                pass
        kb = _inline([("📝 Pick sentence", "go:sentence"),
                      ("⏭ Skip sentence", "go:skip"),
                      ("✖ Cancel", "go:cancel")])
        bot.send_message(chat_id_, mine.preview_text(s.fields), reply_markup=kb)
        # Start the sentence + image searches now so each is ready by its step
        # (overlaps the time the user spends reading the preview).
        mine.prefetch_sentences(s.term)
        mine.prefetch_images(s.term)

    def _enter_image(chat_id_):
        s = _SESSIONS[chat_id_]
        s.image_candidates = mine.image_candidates(s.term)
        if not s.image_candidates:
            bot.send_message(chat_id_, "No images found — skipping image.")
            _enter_confirm(chat_id_)
            return
        s.state = "image"
        media = [telebot.types.InputMediaPhoto(c.thumb_url, caption=str(i + 1))
                 for i, c in enumerate(s.image_candidates)]
        try:
            bot.send_media_group(chat_id_, media)
        except Exception:
            pass  # thumbnails are a nicety; numbered buttons still drive the pick
        bot.send_message(chat_id_, "Pick an image:",
                         reply_markup=_inline(mine.image_rows(s.image_candidates)))

    def _enter_confirm(chat_id_):
        s = _SESSIONS[chat_id_]
        s.state = "confirm"
        kb = _inline([("✅ Apply", "go:apply"), ("✖ Cancel", "go:cancel")])
        bot.send_message(chat_id_, mine.confirm_text(s.fields, s.sentence_fields,
                                                     has_image=s.image is not None),
                         reply_markup=kb)

    def _apply_mine(chat_id_):
        s = _SESSIONS.get(chat_id_)
        if not s:
            return
        try:
            res = mine.commit(s.fields, s.sentence_fields, image=s.image)
        except runlock.Busy:
            # transient: keep the session so the user can just tap Apply again.
            bot.send_message(chat_id_, "⏳ A run is in progress — try again shortly.")
            return
        except Exception as e:
            _SESSIONS.pop(chat_id_, None)
            bot.send_message(chat_id_, f"⚠️ Sync/add failed: {e}")
            return
        _SESSIONS.pop(chat_id_, None)
        if res["status"] == "duplicate":
            bot.send_message(chat_id_, "Already in the deck — nothing added.")
        else:
            bot.send_message(chat_id_,
                             f"✅ Added 「{s.term}」to {res['deck']} & synced.")

    @bot.message_handler(func=lambda m: bool(m.text) and not m.text.startswith("/")
                         and m.text not in (RUN_BUTTON, HELP_BUTTON))
    def _word(m):
        if not _authorized(m.chat.id, chat_id):
            return
        term = m.text.strip()
        defs_conn, ex_conn, audio_conn, acc, gen, freq_conn = _indexes()
        cands = enrich_one.candidates(defs_conn, term, freq_conn=freq_conn)
        if not cands:
            bot.send_message(m.chat.id, f"「{term}」not found — nothing to add.")
            return
        s = mine.Session(chat_id=m.chat.id, term=term, candidates=cands)
        _SESSIONS[m.chat.id] = s
        if len(cands) == 1:
            _enter_preview(m.chat.id, 0)
        else:
            s.state = "candidates"
            bot.send_message(m.chat.id, f"「{term}」— pick a reading:",
                             reply_markup=_inline(mine.candidate_rows(cands)))

    @bot.callback_query_handler(func=lambda c: True)
    def _cb(c):
        if not _authorized(c.message.chat.id, chat_id):
            return

        # Mining-flow prefixes (cand:/go:/sent:/img:) — distinct from the run-menu's
        # bare apply/cancel/all/toggle:. Each branch answers + returns so it never
        # falls through to the run-menu logic below.
        data = c.data
        s = _SESSIONS.get(c.message.chat.id)
        is_mining = data.startswith(("cand:", "go:", "sent:", "img:"))
        if data == "go:cancel":
            _SESSIONS.pop(c.message.chat.id, None)
            bot.answer_callback_query(c.id, "Cancelled")
            return
        if is_mining and not s:        # stale keyboard after restart/cancel/new word
            bot.answer_callback_query(c.id, "Session expired — send the word again")
            return
        if s and ":" in data and data.split(":")[1].isdigit():
            # bounds-guard an indexed pick against the CURRENT session's list — a
            # button from a session replaced by a newer word could be out of range.
            _lists = {"cand": s.candidates, "sent": s.sentences, "img": s.image_candidates}
            lst = _lists.get(data.split(":")[0])
            if lst is not None and int(data.split(":")[1]) >= len(lst):
                bot.answer_callback_query(c.id, "That option expired — send the word again")
                return
        if data.startswith("cand:"):
            _enter_preview(c.message.chat.id, int(data.split(":")[1]))
            bot.answer_callback_query(c.id)
            return
        if data == "go:sentence" and s:
            _, ex_conn, *_ = _indexes()
            s.sentences = mine.sentence_options(s.term, ex_conn=ex_conn)
            s.state = "sentence"
            bot.send_message(c.message.chat.id, "Pick a sentence:",
                             reply_markup=_inline(mine.sentence_rows(s.sentences)))
            bot.answer_callback_query(c.id)
            return
        if data == "go:skip" and s:
            s.sentence_fields = None
            _enter_image(c.message.chat.id)
            bot.answer_callback_query(c.id)
            return
        if data.startswith("sent:") and s:
            _d, _e, _a, acc, gen, _f = _indexes()
            if data == "sent:skip":
                s.sentence_fields = None
            else:
                plain = s.sentences[int(data.split(":")[1])]
                s.sentence_fields = enrich_one.fields_for_sentence(
                    s.term, s.fields.reading, plain, generate=gen, acc=acc)
            _enter_image(c.message.chat.id)
            bot.answer_callback_query(c.id)
            return
        if data.startswith("img:") and s:
            if data == "img:skip":
                s.image = None
            else:
                cand = s.image_candidates[int(data.split(":")[1])]
                s.image = mine.download_image(cand.full_url)
                if s.image is None:
                    bot.send_message(c.message.chat.id,
                                     "Couldn't fetch that image — skipping.")
            _enter_confirm(c.message.chat.id)
            bot.answer_callback_query(c.id)
            return
        if data == "go:apply" and s:
            bot.answer_callback_query(c.id, "Adding…")
            _apply_mine(c.message.chat.id)
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
