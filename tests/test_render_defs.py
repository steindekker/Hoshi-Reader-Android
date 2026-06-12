from ankienrich.defs import render as rd


def test_render_sc_string_and_list():
    assert rd.render_sc("あ") == "あ"
    assert rd.render_sc(["a", "b"]) == "ab"
    assert rd.render_sc("a\nb") == "a<br>b"          # newlines -> <br>
    assert rd.render_sc("<x>") == "&lt;x&gt;"        # escaped


def test_render_sc_tag_style_data():
    node = {"tag": "span", "style": {"fontWeight": "bold", "fontSize": "0.8em"},
            "data": {"name": "見出"}, "content": "語"}
    out = rd.render_sc(node)
    assert out == '<span style="font-weight:bold;font-size:0.8em" data-name="見出">語</span>'


def test_render_sc_ruby_and_void_and_image():
    ruby = {"tag": "ruby", "content": [{"tag": "rb", "content": "西瓜"}, {"tag": "rt", "content": "すいか"}]}
    assert rd.render_sc(ruby) == "<ruby><rb>西瓜</rb><rt>すいか</rt></ruby>"
    assert rd.render_sc({"tag": "br"}) == "<br>"             # void tag, no closing
    assert rd.render_sc({"type": "image", "path": "x.png"}) == ""  # images dropped
    assert rd.render_sc({"type": "structured-content", "content": "z"}) == "z"


def test_render_sc_drops_img_tags():
    # Yomitan inline images are {"tag":"img",...}, not {"type":"image"} — drop them
    # cleanly (no stray <img> with missing src, which 三省堂 accent glyphs would cause).
    assert rd.render_sc({"tag": "img", "path": "x.png"}) == ""
    assert rd.render_sc({"tag": "span", "content": [{"tag": "img"}, "あ"]}) == "<span>あ</span>"


def test_render_sc_drops_relative_query_anchors():
    # Yomitan cross-reference links are relative (?query=…&wildcards=off); they
    # resolve to a dead 127.0.0.1 in any Anki webview. Drop the <a> wrapper, keep
    # the inner content (plain selectable text -> jidoujisho PROCESS_TEXT lookup).
    a = {"tag": "a", "href": "?query=愛する&wildcards=off", "content": "愛する"}
    assert rd.render_sc(a) == "愛する"
    # nested markup inside the anchor is preserved, only the <a> is unwrapped
    nested = {"tag": "a", "href": "?query=正々&wildcards=off",
              "content": [{"tag": "span", "data": {"name": "ア"}, "content": "せい"}, "せい"]}
    assert rd.render_sc(nested) == '<span data-name="ア">せい</span>せい'


def test_render_sc_keeps_absolute_anchors():
    # Real external links (e.g. 大辞泉 geo-references) stay clickable.
    a = {"tag": "a", "href": "https://maps.apple.com/?ll=1,2", "content": "イカ"}
    assert rd.render_sc(a) == '<a href="https://maps.apple.com/?ll=1,2">イカ</a>'


def _je(dict_id, reading, sense_ord, html):
    return {"term": "x", "reading": reading, "dict_id": dict_id,
            "kind": "je", "sense_ord": sense_ord, "html": html}


def test_numbered_glosses_orders_and_caps():
    rows = [
        _je("jmdict", "こうしょう", 2, "connection; relationship"),
        _je("jmdict", "こうしょう", 1, "negotiations; bargaining"),
    ]
    assert rd.numbered_glosses(rows, "こうしょう", cap=4) == \
        "1. negotiations; bargaining<br>2. connection; relationship"


def test_numbered_glosses_cap_and_reading_filter():
    rows = [_je("jmdict", "かける", i, f"g{i}") for i in range(1, 7)]
    rows += [_je("jmdict", "OTHER", 1, "wrong")]
    out = rd.numbered_glosses(rows, "かける", cap=4)
    assert out == "1. g1<br>2. g2<br>3. g3<br>4. g4"   # capped at 4, wrong reading excluded


def test_numbered_glosses_empty():
    assert rd.numbered_glosses([], "あ", cap=4) == ""


def test_numbered_glosses_escapes_html():
    rows = [_je("jmdict", "あ", 1, "arts & crafts <thing>")]
    assert rd.numbered_glosses(rows, "あ", cap=4) == "1. arts &amp; crafts &lt;thing&gt;"


CHAIN = ["sankoku8", "koujien7", "jitsuyo", "daijisen"]


def _jj(dict_id, reading, html):
    return {"term": "x", "reading": reading, "dict_id": dict_id,
            "kind": "jj", "sense_ord": 0, "html": html}


def test_choose_jj_first_in_chain_wins():
    rows = [_jj("daijisen", "あ", "D"), _jj("koujien7", "あ", "K"), _jj("sankoku8", "あ", "S")]
    assert rd.choose_jj(rows, "あ", CHAIN) == ("sankoku8", "S")


def test_choose_jj_reading_disambiguation():
    rows = [_jj("sankoku8", "はし", "bridge"), _jj("sankoku8", "ばし", "wrong")]
    assert rd.choose_jj(rows, "はし", CHAIN) == ("sankoku8", "bridge")


def test_choose_jj_blank_reading_fallback_then_skip():
    rows = [_jj("sankoku8", "", "S-noreading"), _jj("koujien7", "あ", "K")]
    assert rd.choose_jj(rows, "あ", CHAIN) == ("sankoku8", "S-noreading")
    assert rd.choose_jj([], "あ", CHAIN) is None
    # dict not in chain is ignored
    assert rd.choose_jj([_jj("smk8", "あ", "X")], "あ", CHAIN) is None


def test_assemble_meaning_jj_and_je():
    out = rd.assemble_meaning(("sankoku8", "<b>def</b>"), "1. a; b")
    assert out == ('<div class="aa-jj" data-dict="sankoku8"><b>def</b></div>'
                   '<div class="aa-je">1. a; b</div>')


def test_assemble_meaning_english_only_jj_only_and_empty():
    assert rd.assemble_meaning(None, "1. a") == '<div class="aa-je">1. a</div>'
    assert rd.assemble_meaning(("sankoku8", "x"), "") == '<div class="aa-jj" data-dict="sankoku8">x</div>'
    assert rd.assemble_meaning(None, "") == ""


def test_source_label():
    labels = {"sankoku8": "三省堂", "jmdict": "JMdict"}
    assert rd.source_label(("sankoku8", "x"), has_je=True, labels=labels) == "三省堂"
    assert rd.source_label(None, has_je=True, labels=labels) == "JMdict"
    assert rd.source_label(None, has_je=False, labels=labels) == "—"
