from ankialign import mapping

def test_map_mynote_derives_term_and_renames():
    src = {
        "Sentence": "それを{[一|ひと]つ;2}{くださ[い=る];Y3}。",
        "Translation": "Please give me one of those.",
        "Target Word": "{[一|ひと]つ;2}",
        "Definitions": "<i>(adj-no)</i> one",
        "Screenshot": "",
        "Sentence Audio": "",
        "Word Audio": "[sound:w.mp3]",
        "Images": "<img src='i.jpg'>",
        "Example Sentences": "x<br>y",
        "Frequency": "1234",
        "Pitch": "<svg>...</svg>",
    }
    out = mapping.map_mynote(src)
    assert out["Term"] == "一つ"
    assert out["Reading"] == "ひとつ"
    assert out["Furigana"] == "一[ひと]つ"
    assert out["Sentence"] == src["Sentence"]
    assert out["Meaning"] == "<i>(adj-no)</i> one"
    assert out["Term Audio"] == "[sound:w.mp3]"
    assert out["Image"] == "<img src='i.jpg'>"   # Screenshot empty -> Images
    assert out["Pitch Accent"] == "<svg>...</svg>"
    assert out["Frequency"] == "1234"
    assert "Translation" not in out and "Example Sentences" not in out

def test_map_mynote_prefers_screenshot_for_image():
    src = {"Target Word": "一", "Screenshot": "<img src='s.jpg'>", "Images": "<img src='i.jpg'>"}
    assert mapping.map_mynote(src)["Image"] == "<img src='s.jpg'>"

def test_map_yuuna_cleans_term_keeps_rest():
    src = {"Term": "{[土砂|どしゃ];1}", "Reading": "どしゃ", "Furigana": "土砂[どしゃ]",
           "Sentence": "", "Meaning": "earth and sand", "Notes": "",
           "Image": "", "Term Audio": "[sound:y.mp3]", "Pitch Accent": "ど<span>しゃ</span>",
           "Expanded Meaning": "", "Collapsed Meaning": "", "Sentence Audio": ""}
    out = mapping.map_yuuna(src)
    assert out["Term"] == "土砂"
    assert out["Reading"] == "どしゃ"
    assert out["Meaning"] == "earth and sand"
    assert out["Term Audio"] == "[sound:y.mp3]"

def test_map_kanjistudy_core_only():
    src = {"ID": "x", "Entry": "有[あ,ある;k1]り そう[;h,a]", "EntryFurigana": "有[あ]りそう",
           "Meaning": "probable, likely", "Sentence": "", "Translation": "t",
           "Screenshot": "s", "Word Audio": "[sound:k.mp3]", "Sentence Audio": "sa",
           "Images": "im", "Example Sentences": "ex", "Frequency": "99"}
    out = mapping.map_kanjistudy(src)
    assert out == {
        "Term": "有りそう", "Reading": "ありそう", "Furigana": "有[あ]りそう",
        "Meaning": "probable, likely", "Term Audio": "[sound:k.mp3]",
    }

def test_mappers_registry():
    assert set(mapping.MAPPERS) == {
        "MyNote", "jidoujisho Yuuna", "Kanji Study Word Model v3"}
