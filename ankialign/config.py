"""Static configuration for this single-user tool: target note type, the
logical-role -> field-name map, in-scope deck filter, and AnkiWeb credentials."""
import os

NOTETYPE = "jidoujisho Kinomoto"

# Logical role -> real Kinomoto field name. The one place to retarget fields.
TERM = "Term"
READING = "Reading"
TERM_FURIGANA = "Furigana"
SENTENCE = "Sentence"
SENTENCE_FURIGANA = "SentenceFurigana"
TERM_AUDIO = "Term Audio"
DEFINITION = "Meaning"
PITCH = "Pitch Accent"
CLOZE_BEFORE = "Cloze Before"
CLOZE_INSIDE = "Cloze Inside"
CLOZE_AFTER = "Cloze After"
ORIGINAL_MEANING = "Original Meaning"
DEFINITION_SOURCE = "Definition Source"

# A note is in scope unless one of its decks' names contains one of these.
EXCLUDE_DECK_SUBSTRINGS = ["Core2.3k", "文法"]

# Definition enrichment.
JJ_CHAIN = ["sankoku8", "koujien7", "jitsuyo", "daijisen"]  # first hit wins
JE_DICT = "jmdict"
GLOSS_CAP = 4
DICT_LABELS = {
    "sankoku8": "三省堂", "koujien7": "広辞苑", "jitsuyo": "実用",
    "daijisen": "大辞泉", "jmdict": "JMdict",
}
ENRICHED_TAG = "ankialign::enriched"
NO_DEF_TAG = "ankialign::no-definition"
NO_HIT_DROP_THRESHOLD = 20

# local-audio-yomichan add-on (AnkiWeb code) — source of term-audio backfill.
LOCAL_AUDIO_ADDON_ID = "1045800357"

# How many before/after samples to show in dry-run output.
SAMPLE_N = 5


def in_scope(deck_name: str) -> bool:
    return not any(sub in deck_name for sub in EXCLUDE_DECK_SUBSTRINGS)


def deck_exclusion_search() -> str:
    """Anki search clause excluding out-of-scope decks."""
    return " ".join(f"-deck:*{sub}*" for sub in EXCLUDE_DECK_SUBSTRINGS)


def ankiweb_credentials() -> tuple[str, str]:
    """(username, password) from env. Raises if unset."""
    user = os.environ.get("ANKIWEB_USERNAME")
    pw = os.environ.get("ANKIWEB_PASSWORD")
    if not user or not pw:
        raise RuntimeError(
            "set ANKIWEB_USERNAME and ANKIWEB_PASSWORD in the environment")
    return user, pw
