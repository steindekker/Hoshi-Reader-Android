"""Static configuration for this single-user tool: target note type, the
logical-role -> field-name map, and AnkiWeb credentials."""
import os

NOTETYPE = "Hoshi"  # renamed from "jidoujisho Kinomoto" on 2026-10-16
DEFAULT_DECK = "Default"

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
# Cloze whose text genuinely differs from its Sentence (different subtitle line at
# mining time) — can't be auto-repaired from Sentence, so it's flagged for review.
CLOZE_MISMATCH_TAG = "ankienrich::cloze-mismatch"
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

# Term-audio loudness leveling (audio/loudness.py). Clips reach a card from
# several places — mined by jidoujisho on the phone, pulled from the local-audio
# store, the odd ogg — and nobody masters them together, so their integrated
# loudness spans ~9 LU and review volume lurches from card to card.
# -16 LUFS is the mono-speech convention; the 1 dBTP ceiling leaves room for the
# intersample overshoot a lossy re-encode can add.
AUDIO_TARGET_LUFS = -16.0
AUDIO_PEAK_CEILING_DBTP = -1.0
# Below this much gain a clip is already fine, and re-encoding it would spend a
# generation of quality on an inaudible change — keep the original bytes instead.
AUDIO_NORM_TOLERANCE_DB = 0.5
# Leveled clips are written under this prefix and the note repointed at them.
# The prefix IS the "already leveled" marker, so the state lives in the
# collection: re-runs measure nothing, and a restored backup stays consistent
# with its media. Changing any constant above will NOT re-level existing clips.
AUDIO_NORM_PREFIX = "norm_"
# ffmpeg is a subprocess, so threads scale it; clips are held in memory between
# the parallel encode and the serial collection write, hence the batch cap.
AUDIO_NORM_WORKERS = 8
AUDIO_NORM_BATCH = 128

# Delimits the generated, scoped dictionary CSS appended to the notetype styling.
# template.apply (authoritative design) and defs.enrich.apply_dict_styles both
# split on this exact string to refresh the block in place — keep it singular.
DICT_STYLES_MARKER = "/* ---- AnkiAlign dict styles (generated) ---- */"

# Frequency enrichment. The Frequency field holds the JPDB rank (raw integer as a
# string), asserted on every card from the freq index — a pure (term, reading)
# function, so no tags/overwrite flag (see fill.py).
FREQUENCY = "Frequency"
IMAGE = "Image"

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


def telegram_credentials() -> tuple[str, str]:
    """(bot token, chat id) from env. Raises if unset. Chat id stays a string;
    the bot casts it to int, the notifier sends it as-is."""
    token = os.environ.get("TELEGRAM_BOT_TOKEN")
    chat = os.environ.get("TELEGRAM_CHAT_ID")
    if not token or not chat:
        raise RuntimeError(
            "set TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID in the environment")
    return token, chat
