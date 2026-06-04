"""Static configuration for the unify pass (single-user tool)."""
import os

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


# --- definition enrichment ---
JJ_CHAIN = ["sankoku8", "koujien7", "jitsuyo", "daijisen"]  # first hit wins
JE_DICT = "jmdict"
GLOSS_CAP = 4

DICT_LABELS = {
    "sankoku8": "三省堂", "koujien7": "広辞苑", "jitsuyo": "実用",
    "daijisen": "大辞泉", "jmdict": "JMdict",
}

ORIGINAL_MEANING_FIELD = "Original Meaning"
DEFINITION_SOURCE_FIELD = "Definition Source"
ENRICHED_TAG = "ankialign::enriched"
NO_DEF_TAG = "ankialign::no-definition"
NO_HIT_DROP_THRESHOLD = 20

_USER_FILES = os.path.join(os.path.dirname(__file__), "user_files")
DEF_INDEX_PATH = os.path.join(_USER_FILES, "defs.sqlite")
DICT_STYLES_PATH = os.path.join(_USER_FILES, "dict_styles.css")
