from ankialign import render_defs as rd


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
