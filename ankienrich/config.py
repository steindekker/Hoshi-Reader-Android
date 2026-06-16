"""Static configuration for this single-user tool: target note type, the
logical-role -> field-name map, and AnkiWeb credentials."""
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
EXPANDED_MEANING = "Expanded Meaning"

# Definition enrichment.
JJ_CHAIN = ["sankoku8", "koujien7", "jitsuyo", "daijisen"]  # first hit wins
JE_DICT = "jmdict"
GLOSS_CAP = 4
DICT_LABELS = {
    "sankoku8": "三省堂", "koujien7": "広辞苑", "jitsuyo": "実用",
    "daijisen": "大辞泉", "jmdict": "JMdict",
}
ENRICHED_TAG = "ankienrich::enriched"
NO_DEF_TAG = "ankienrich::no-definition"
NO_HIT_DROP_THRESHOLD = 20

# Example-sentence enrichment. Up to EXAMPLE_CAP sentences per card, filled in
# EXAMPLE_SOURCES priority order with cross-source dedupe. Jitendex leads (ships
# furigana + EN but ~1 example/word); Tatoeba tops up the thin words (same corpus,
# plain JP + EN); 斎藤 / 用例.jp backfill what Tatoeba misses. 用例.jp passages are
# multi-sentence literary excerpts → parse.yourei keeps only the headword's sentence;
# EXAMPLE_MAX_LEN then drops any residual over-long example (any source) so cards
# never carry a paragraph.
EXAMPLES = "Examples"
EXAMPLE_SOURCES = ["jitendex", "tatoeba", "saito", "yourei"]
EXAMPLE_CAP = 3
EXAMPLE_BUILD_CAP = 12  # max stored per (dict, term, reading); ≥ CAP for dedupe headroom
EXAMPLE_MAX_LEN = 60    # skip examples whose plain JP exceeds this many chars
EX_LABELS = {"jitendex": "Jitendex", "tatoeba": "Tatoeba", "saito": "斎藤", "yourei": "用例.jp"}
EXAMPLES_TAG = "ankienrich::examples"
NO_EXAMPLE_TAG = "ankienrich::no-example"

# Delimits the generated, scoped dictionary CSS appended to the notetype styling.
# template.apply (authoritative design) and defs.enrich.apply_dict_styles both
# split on this exact string to refresh the block in place — keep it singular.
DICT_STYLES_MARKER = "/* ---- AnkiAlign dict styles (generated) ---- */"

# local-audio-yomichan add-on (AnkiWeb code) — source of term-audio backfill.
LOCAL_AUDIO_ADDON_ID = "1045800357"

# How many before/after samples to show in dry-run output.
SAMPLE_N = 5


def ankiweb_credentials() -> tuple[str, str]:
    """(username, password) from env. Raises if unset."""
    user = os.environ.get("ANKIWEB_USERNAME")
    pw = os.environ.get("ANKIWEB_PASSWORD")
    if not user or not pw:
        raise RuntimeError(
            "set ANKIWEB_USERNAME and ANKIWEB_PASSWORD in the environment")
    return user, pw
