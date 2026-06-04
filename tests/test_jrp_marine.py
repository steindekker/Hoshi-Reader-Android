import pytest

def _marine():
    try:
        import marine  # noqa: F401
        return True
    except Exception:
        return False

@pytest.mark.skipif(not _marine(), reason="marine not installed")
def test_marine_fill_returns_positions_or_none():
    from ankialign.jrp_fill.unit import Unit
    from ankialign.jrp_fill.marine_tail import marine_fill
    u = Unit("誹謗中傷", "誹謗中傷", "ひぼうちゅうしょう", "ひぼうちゅうしょう",
             True, False, ())
    got = marine_fill(u)
    assert got is None or (isinstance(got, tuple) and all(isinstance(x, int) for x in got))
