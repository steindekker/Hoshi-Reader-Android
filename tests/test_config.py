from ankialign import config

def test_in_scope_excludes_core_and_grammar():
    assert config.in_scope("言葉文章") is True
    assert config.in_scope("言葉::文章") is True
    assert config.in_scope("言葉Core2.3k") is False
    assert config.in_scope("言葉::Core2.3k") is False
    assert config.in_scope("文法") is False
    assert config.in_scope("文法::助詞") is False

def test_known_constants():
    assert config.TARGET_NOTETYPE == "jidoujisho Kinomoto"
    assert "MyNote" in config.SOURCE_NOTETYPES
    assert "jidoujisho Yuuna" in config.SOURCE_NOTETYPES
    assert "Kanji Study Word Model v3" in config.SOURCE_NOTETYPES

def test_enrichment_constants():
    from ankialign import config
    assert config.JJ_CHAIN == ["sankoku8", "koujien7", "jitsuyo", "daijisen"]
    assert config.JE_DICT == "jmdict"
    assert config.GLOSS_CAP == 4
    assert config.DICT_LABELS["sankoku8"] == "三省堂"
    assert config.DICT_LABELS["jmdict"] == "JMdict"
    assert config.ORIGINAL_MEANING_FIELD == "Original Meaning"
    assert config.DEFINITION_SOURCE_FIELD == "Definition Source"
    assert config.ENRICHED_TAG == "ankialign::enriched"
    assert config.NO_DEF_TAG == "ankialign::no-definition"
    assert config.NO_HIT_DROP_THRESHOLD == 20
    assert config.DEF_INDEX_PATH.endswith("user_files/defs.sqlite")
    assert config.DICT_STYLES_PATH.endswith("user_files/dict_styles.css")
