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

def test_kinomoto_css_has_definition_styles():
    import os
    from ankialign import template
    css = template._css()
    assert ".aa-jj" in css and ".aa-je" in css
    assert ".aa-jj ruby rt" in css            # furigana polish present
    assert ".aa-je > summary" in css          # English toggle styling present
