import pytest

anki = pytest.importorskip("anki")
from anki.collection import Collection

from ankienrich import cloze, config
from ankienrich.jrp_fill.accent import AccentIndex


def _kinomoto(col):
    m = col.models.new(config.NOTETYPE)
    for fn in (config.TERM, config.READING, config.TERM_FURIGANA, config.SENTENCE,
               config.SENTENCE_FURIGANA, config.CLOZE_BEFORE, config.CLOZE_INSIDE,
               config.CLOZE_AFTER, config.DEFINITION, config.PITCH, config.TERM_AUDIO):
        col.models.add_field(m, col.models.new_field(fn))
    t = col.models.new_template("Card 1")
    t["qfmt"] = "{{%s}}" % config.TERM
    t["afmt"] = "{{%s}}" % config.READING
    col.models.add_template(m, t)
    col.models.add(m)
    return col.models.by_name(config.NOTETYPE)


def test_strip_markup_drops_jrp_and_furigana_keeps_text():
    # {term;accent} -> term ; [base|reading] -> base ; <br> kept
    assert cloze.strip_markup("{猫;ねこ}が[歩|ある]いた。<br>") == "猫が歩いた。<br>"


def test_strip_markup_normalizes_fullwidth_space():
    # ideographic space -> ASCII space, so cloze never reintroduces it
    assert cloze.strip_markup("{今;いま}は　そげな[話|はなし]は") == "今は そげな話は"


def test_resync_strips_speaker_label():
    # Sentence lost its （name）label (subtitle step); reproject drops it from cloze too
    assert cloze.resync("（長嶺）わっぜ ", "功を焦った", "のう。",
                        "わっぜ功を焦ったのう。") == ("わっぜ", "功を焦った", "のう。")


def test_resync_normalizes_ideographic_space():
    # cloze keeps U+3000; Sentence normalized it to ASCII — adopt the Sentence's space
    assert cloze.resync("＜奥越を", "手中に収めた", "　聖夷軍は➨",
                        "＜奥越を手中に収めた 聖夷軍は➨") == ("＜奥越を", "手中に収めた", " 聖夷軍は➨")


def test_resync_drops_ruby_html():
    # cloze carries <ruby> furigana; the plain (markup-stripped) Sentence does not
    assert cloze.resync(
        "あの時話し", "そびれた",
        "件……<ruby>聞<rt>き</rt></ruby>かせてくれる<ruby>気<rt>き</rt></ruby>になったのか",
        "あの時話しそびれた件……聞かせてくれる気になったのか",
    ) == ("あの時話し", "そびれた", "件……聞かせてくれる気になったのか")


def test_resync_content_mismatch_returns_none():
    # cloze text is a different subtitle line — no way to reproject onto Sentence
    assert cloze.resync("", "しかるべき", "処置を", "ダルの実力は折り紙付きだ") is None


def test_resync_already_synced_roundtrips():
    assert cloze.resync("猫が", "歩", "いた。", "猫が歩いた。") == ("猫が", "歩", "いた。")


def test_locate_verbatim():
    assert cloze.locate("猫", "ねこ", "猫が歩いた。", None) == ("", "猫", "が歩いた。")


def test_locate_unfindable_returns_none():
    # not verbatim -> falls through to tokenization, which needs the real index
    acc = AccentIndex.load()
    assert cloze.locate("犬", "いぬ", "猫が歩いた。", acc) is None


@pytest.fixture
def col(tmp_path):
    c = Collection(str(tmp_path / "t.anki2"))
    m = _kinomoto(c)
    c.models.set_current(m)
    deck = c.decks.id("言葉文章")

    def add(term, reading, sentence, before="", inside="", after=""):
        n = c.new_note(m)
        n[config.TERM] = term
        n[config.READING] = reading
        n[config.SENTENCE] = sentence
        n[config.CLOZE_BEFORE] = before
        n[config.CLOZE_INSIDE] = inside
        n[config.CLOZE_AFTER] = after
        c.add_note(n, deck)
        return n.id

    add("猫", "ねこ", "{猫;ねこ}が[歩|ある]いた。")          # fillable verbatim
    add("犬", "いぬ", "犬だ。", "", "犬", "だ。")            # has cloze -> skip
    add("鳥", "とり", "")                                    # no sentence -> skip
    yield c
    c.close()


def _by_term(col, term):
    return col.get_note(col.find_notes(f'"Term:{term}"')[0])


def test_run_fills_only_empty_verbatim(col):
    # object() sentinel: tokenizer (which would need the AccentIndex) is never
    # reached because the term is found verbatim.
    res = cloze.run(col, apply=True, acc=object())
    assert res == {"filled": 1, "resynced": 0, "skipped": 0, "mismatched": 0}
    n = _by_term(col, "猫")
    assert (n[config.CLOZE_BEFORE], n[config.CLOZE_INSIDE], n[config.CLOZE_AFTER]) == ("", "猫", "が歩いた。")
    assert _by_term(col, "犬")[config.CLOZE_INSIDE] == "犬"     # untouched


def test_run_idempotent(col):
    cloze.run(col, apply=True, acc=object())
    assert cloze.run(col, apply=True, acc=object()) == {
        "filled": 0, "resynced": 0, "skipped": 0, "mismatched": 0}


def test_overwrite_resplits_stale_only(col):
    cloze.run(col, apply=True, acc=object())                       # fill empty (猫)
    assert cloze.run(col, apply=True, acc=object())["filled"] == 0

    n = _by_term(col, "犬")                                        # corrupt its split
    n[config.CLOZE_BEFORE], n[config.CLOZE_INSIDE], n[config.CLOZE_AFTER] = "X", "Y", "Z"
    col.update_note(n)

    # default leaves the existing (stale) split alone
    assert cloze.run(col, apply=True, acc=object())["filled"] == 0
    assert _by_term(col, "犬")[config.CLOZE_INSIDE] == "Y"

    # overwrite re-splits only the stale one
    assert cloze.run(col, apply=True, acc=object(), overwrite=True)["filled"] == 1
    n2 = _by_term(col, "犬")
    assert (n2[config.CLOZE_BEFORE], n2[config.CLOZE_INSIDE], n2[config.CLOZE_AFTER]) == ("", "犬", "だ。")


def test_run_resyncs_normalization_desync(col):
    # existing cloze that reconstructs the SAME sentence but carries a stripped
    # speaker label + full-width space is re-projected onto the plain Sentence,
    # even in default (non-overwrite) mode — no locate() needed.
    m = col.models.by_name(config.NOTETYPE)
    n = col.new_note(m)
    n[config.TERM], n[config.READING] = "歩く", "あるく"
    n[config.SENTENCE] = "{猫;ねこ}が[歩|ある]いた。"          # plain -> 猫が歩いた。
    n[config.CLOZE_BEFORE] = "（誰か）猫が　"                  # label + U+3000
    n[config.CLOZE_INSIDE] = "歩"
    n[config.CLOZE_AFTER] = "いた。"
    col.add_note(n, col.decks.id("言葉文章"))

    res = cloze.run(col, apply=True, acc=object())
    assert res["resynced"] == 1 and res["mismatched"] == 0
    got = _by_term(col, "歩く")
    assert (got[config.CLOZE_BEFORE], got[config.CLOZE_INSIDE], got[config.CLOZE_AFTER]) == (
        "猫が", "歩", "いた。")
    assert not got.has_tag(config.CLOZE_MISMATCH_TAG)
    # idempotent: a second pass is a no-op
    assert cloze.run(col, apply=True, acc=object())["resynced"] == 0


def test_run_flags_and_unflags_source_mismatch(col):
    # cloze text is a genuinely different line from the Sentence -> flag for review
    m = col.models.by_name(config.NOTETYPE)
    n = col.new_note(m)
    n[config.TERM], n[config.READING] = "然るべき", "しかるべき"
    n[config.SENTENCE] = "ダルの実力は折り紙付きだ"
    n[config.CLOZE_BEFORE] = ""
    n[config.CLOZE_INSIDE] = "しかるべき"
    n[config.CLOZE_AFTER] = "処置を行う"
    col.add_note(n, col.decks.id("言葉文章"))
    nid = n.id

    res = cloze.run(col, apply=True, acc=object())
    assert res["mismatched"] == 1 and res["resynced"] == 0
    flagged = col.get_note(nid)
    assert flagged.has_tag(config.CLOZE_MISMATCH_TAG)
    assert (flagged[config.CLOZE_INSIDE]) == "しかるべき"        # left untouched

    # fix the Sentence so it now matches; the flag self-heals
    flagged[config.SENTENCE] = "しかるべき処置を行う"
    col.update_note(flagged)
    cloze.run(col, apply=True, acc=object())
    assert not col.get_note(nid).has_tag(config.CLOZE_MISMATCH_TAG)
