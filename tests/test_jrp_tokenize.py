import os, shutil
import pytest

IDX = os.path.join(os.path.dirname(__file__), "..",
                   "ankialign", "data", "accent_index.marshal.gz")
pytestmark = pytest.mark.skipif(
    shutil.which("python") is None or not os.path.exists(IDX),
    reason="needs accent index")

def _sudachi_available():
    try:
        import sudachipy  # noqa: F401
        return True
    except Exception:
        return False

@pytest.mark.skipif(not _sudachi_available(), reason="sudachipy not installed")
def test_units_roundtrip_and_accent():
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    from ankialign import markup
    acc = AccentIndex.load(IDX)
    s = "異世界には魔物がいる"
    us = units(s, acc)
    out = emit_sentence(us)
    # round-trips to the original surface
    assert markup.jrp_surface(out) == s
    # 異世界 is a compound that Mode C should keep whole and pitch
    assert "異世界" in out and "{" in out

@pytest.mark.skipif(not _sudachi_available(), reason="sudachipy not installed")
def test_split_cascade_recovers_overmerge():
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    from ankialign import markup
    acc = AccentIndex.load(IDX)
    out = emit_sentence(units("前途多難だ", acc))
    assert markup.jrp_surface(out) == "前途多難だ"
