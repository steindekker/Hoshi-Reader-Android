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
    assert res == {"filled": 1, "skipped": 0}
    n = _by_term(col, "猫")
    assert (n[config.CLOZE_BEFORE], n[config.CLOZE_INSIDE], n[config.CLOZE_AFTER]) == ("", "猫", "が歩いた。")
    assert _by_term(col, "犬")[config.CLOZE_INSIDE] == "犬"     # untouched


def test_run_idempotent(col):
    cloze.run(col, apply=True, acc=object())
    assert cloze.run(col, apply=True, acc=object()) == {"filled": 0, "skipped": 0}


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
