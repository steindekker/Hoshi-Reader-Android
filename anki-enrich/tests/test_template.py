import os
import tempfile

import pytest
from anki.collection import Collection

from ankienrich import config, paths, template


_ALL_FIELDS = [
    config.TERM, config.READING, config.TERM_FURIGANA, config.SENTENCE,
    config.SENTENCE_FURIGANA, config.CLOZE_BEFORE, config.CLOZE_INSIDE,
    config.CLOZE_AFTER, config.DEFINITION, "Expanded Meaning", "Collapsed Meaning",
    "Notes", "Context", "Frequency", config.PITCH, "Image",
    config.TERM_AUDIO, "Sentence Audio",
]


def _col(tmp):
    col = Collection(os.path.join(tmp, "c.anki2"))
    m = col.models.new(config.NOTETYPE)
    for fname in _ALL_FIELDS:
        col.models.add_field(m, col.models.new_field(fname))
    t = col.models.new_template("jidoujisho Kinomoto")
    t["qfmt"] = f"{{{{{config.TERM}}}}}"
    t["afmt"] = f"{{{{{config.SENTENCE}}}}}"
    col.models.add_template(m, t)
    m["css"] = "OLD CSS"
    col.models.add(m)
    return col


def test_apply_writes_repo_design(tmp_path, monkeypatch):
    col = _col(str(tmp_path))
    try:
        template.apply(col)
        m = col.models.by_name(config.NOTETYPE)
        assert m["tmpls"][0]["qfmt"] == open(
            os.path.join(paths.CARD_DIR, "front.html"), encoding="utf-8").read()
        assert m["tmpls"][0]["afmt"] == open(
            os.path.join(paths.CARD_DIR, "back.html"), encoding="utf-8").read()
        assert m["css"].startswith(open(
            os.path.join(paths.CARD_DIR, "style.css"), encoding="utf-8").read().rstrip())
        assert "OLD CSS" not in m["css"]
    finally:
        col.close()


def test_apply_appends_dict_styles_when_present(tmp_path, monkeypatch):
    styles = tmp_path / "dict_styles.css"
    styles.write_text(".aa-jj{color:red}", encoding="utf-8")
    monkeypatch.setattr(paths, "DICT_STYLES", str(styles))
    col = _col(str(tmp_path))
    try:
        template.apply(col)
        m = col.models.by_name(config.NOTETYPE)
        assert config.DICT_STYLES_MARKER in m["css"]
        assert ".aa-jj{color:red}" in m["css"]
    finally:
        col.close()


def test_apply_idempotent(tmp_path):
    col = _col(str(tmp_path))
    try:
        template.apply(col)
        css1 = col.models.by_name(config.NOTETYPE)["css"]
        template.apply(col)
        css2 = col.models.by_name(config.NOTETYPE)["css"]
        assert css1 == css2
    finally:
        col.close()
