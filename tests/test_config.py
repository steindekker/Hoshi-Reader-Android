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
