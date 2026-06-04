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
