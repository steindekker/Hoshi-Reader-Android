"""Static configuration for the unify pass (single-user tool)."""

TARGET_NOTETYPE = "jidoujisho Kinomoto"

SOURCE_NOTETYPES = [
    "MyNote",
    "jidoujisho Yuuna",
    "Kanji Study Word Model v3",
]

# A note is in scope unless any of its decks' names contain one of these.
EXCLUDE_DECK_SUBSTRINGS = ["Core2.3k", "文法"]

# How many before/after samples per source to show in the preview dialog.
SAMPLE_N = 5

# local-audio-yomichan add-on (AnkiWeb code) — source of term-audio backfill.
LOCAL_AUDIO_ADDON_ID = "1045800357"


def in_scope(deck_name: str) -> bool:
    """True if a deck name is an in-scope mined deck (not Core2.3k / 文法)."""
    return not any(sub in deck_name for sub in EXCLUDE_DECK_SUBSTRINGS)
