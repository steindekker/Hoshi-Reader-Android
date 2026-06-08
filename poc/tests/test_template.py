import pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankialign import template, config

KINOMOTO_FIELDS = ["Term", "Reading", "Furigana", "Sentence", "SentenceFurigana",
    "Cloze Before", "Cloze Inside", "Cloze After", "Meaning", "Expanded Meaning",
    "Collapsed Meaning", "Notes", "Context", "Frequency", "Pitch Accent", "Image",
    "Term Audio", "Sentence Audio"]


@pytest.fixture
def col(tmp_path):
    c = Collection(str(tmp_path / "t.anki2"))
    m = c.models.new(config.TARGET_NOTETYPE)
    for f in KINOMOTO_FIELDS:
        c.models.add_field(m, c.models.new_field(f))
    t = c.models.new_template("Card 1")
    t["qfmt"] = '<div id="sentence">{{Sentence}}</div>'
    t["afmt"] = '<div id="sentence" data-jrp-generate>{{Sentence}}</div>'
    c.models.add_template(m, t)
    c.models.add(m)
    yield c
    c.close()


def test_apply_injects_strip_marker_and_css(col):
    template.apply(col)
    m = col.models.by_name(config.TARGET_NOTETYPE)
    assert "ankialign-front-strip" in m["tmpls"][0]["qfmt"]
    assert "AnkiAlign custom" in m["css"]
    assert "Hiragino Mincho ProN" in m["css"]   # real font stack, not a placeholder
    assert "{LAPIS" not in m["css"]              # no unsubstituted f-string placeholder

def test_apply_is_idempotent(col):
    template.apply(col); first = col.models.by_name(config.TARGET_NOTETYPE)["css"]
    template.apply(col); second = col.models.by_name(config.TARGET_NOTETYPE)["css"]
    assert first == second

def test_apply_replaces_stale_custom_block(col):
    # Regression: an earlier run can leave a definition-less AnkiAlign block.
    # apply() must REPLACE it (not skip on marker presence) so an updated
    # kinomoto.css lands; otherwise .aa-je never reaches the card.
    m = col.models.by_name(config.TARGET_NOTETYPE)
    m["css"] = ".card{}\n\n/* ---- AnkiAlign custom (OLD) ---- */\n.aa-jj{color:OLD}"
    col.models.update_dict(m)
    template.apply(col)
    css = col.models.by_name(config.TARGET_NOTETYPE)["css"]
    assert ".aa-je" in css                       # updated block landed
    assert "color:OLD" not in css                # stale block replaced, not kept
    assert css.count("AnkiAlign custom") == 1     # marker not duplicated

def test_apply_makes_pitch_popup_hiragana(col):
    # The JRP managed script builds the hover pitch graph from
    # `split_moras(reading)` which defaults to katakana. apply() must flip the
    # graph call to hiragana in both qfmt and afmt; the mora-count calls
    # (split_moras(reading).length) are script-agnostic and must stay untouched.
    m = col.models.by_name(config.TARGET_NOTETYPE)
    snippet = ("<div>{{Sentence}}</div><script>const moras = split_moras(reading);"
               " const n = split_moras(reading).length;</script>")
    m["tmpls"][0]["qfmt"] = snippet
    m["tmpls"][0]["afmt"] = snippet
    col.models.update_dict(m)
    template.apply(col)
    t = col.models.by_name(config.TARGET_NOTETYPE)["tmpls"][0]
    for key in ("qfmt", "afmt"):
        assert "const moras = split_moras(reading, true);" in t[key]
        assert "const moras = split_moras(reading);" not in t[key]
        assert "split_moras(reading).length" in t[key]   # count call untouched


def test_apply_pitch_popup_hiragana_is_idempotent(col):
    m = col.models.by_name(config.TARGET_NOTETYPE)
    m["tmpls"][0]["afmt"] = "x = split_moras(reading); y"
    col.models.update_dict(m)
    template.apply(col)
    once = col.models.by_name(config.TARGET_NOTETYPE)["tmpls"][0]["afmt"]
    template.apply(col)
    twice = col.models.by_name(config.TARGET_NOTETYPE)["tmpls"][0]["afmt"]
    assert once == twice
    assert once.count("split_moras(reading, true)") == 1


def test_kinomoto_css_has_definition_styles():
    import os
    from ankialign import template
    css = template._css()
    assert ".aa-jj" in css and ".aa-je" in css
    assert ".aa-jj ruby rt" in css            # furigana polish present
    assert "border-top" in css                # the .aa-je divider between J-J and English
