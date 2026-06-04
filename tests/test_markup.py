from ankialign import markup

def test_jrp_surface():
    assert markup.jrp_surface("{[異変|いへん];0}") == "異変"
    assert markup.jrp_surface("{[一|ひと]つ;2}") == "一つ"
    assert markup.jrp_surface("{[気付|きづ][いた=く];Y2}") == "気付いた"
    assert markup.jrp_surface("{くださ[い=る];Y3}") == "ください"
    assert markup.jrp_surface("{それ;1}") == "それ"
    assert markup.jrp_surface("一") == "一"
    assert markup.jrp_surface("それ") == "それ"

def test_jrp_to_furigana():
    assert markup.jrp_to_furigana("{[異変|いへん];0}") == "異変[いへん]"
    assert markup.jrp_to_furigana("{[一|ひと]つ;2}") == "一[ひと]つ"
    assert markup.jrp_to_furigana("{[気付|きづ][いた=く];Y2}") == "気付[きづ]いた"
    assert markup.jrp_to_furigana("一") == "一"

def test_jrp_reading():
    assert markup.jrp_reading("{[異変|いへん];0}") == "いへん"
    assert markup.jrp_reading("{[一|ひと]つ;2}") == "ひとつ"
    assert markup.jrp_reading("{[気付|きづ][いた=く];Y2}") == "きづいた"
    assert markup.jrp_reading("それ") == "それ"
    assert markup.jrp_reading("一") == ""

def test_entryfurigana_split():
    assert markup.entryfurigana_surface("有[あ]りそう") == "有りそう"
    assert markup.entryfurigana_reading("有[あ]りそう") == "ありそう"
    assert markup.entryfurigana_surface("気[き] 合[あい]") == "気合"
    assert markup.entryfurigana_reading("気[き] 合[あい]") == "きあい"
