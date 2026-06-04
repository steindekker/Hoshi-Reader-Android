from ankialign.jrp_fill.unit import Unit
from ankialign.jrp_fill.emit import emit_unit, emit_sentence
from ankialign import markup

def U(**kw):
    base = dict(surface="", lemma="", reading="", lemma_reading="",
                is_content=True, is_yougen=False, accents=(), ambiguous=False)
    base.update(kw)
    return Unit(**base)

def test_bare_particle():
    assert emit_unit(U(surface="は", reading="は", is_content=False)) == "は"

def test_kanji_no_accent_gets_furigana_only():
    u = U(surface="異端", reading="いたん", lemma="異端", lemma_reading="いたん", accents=())
    assert emit_unit(u) == "[異端|いたん]"

def test_kanji_with_accent_is_wrapped():
    u = U(surface="異変", reading="いへん", lemma="異変", lemma_reading="いへん", accents=(0,))
    assert emit_unit(u) == "{[異変|いへん];0}"

def test_yougen_flag_and_okurigana():
    u = U(surface="気付いた", reading="きづいた", lemma="気付く",
          lemma_reading="きづく", is_yougen=True, accents=(2,))
    assert emit_unit(u) == "{[気付|きづ]いた;Y2}"

def test_multiple_accents():
    u = U(surface="世界", reading="せかい", lemma="世界",
          lemma_reading="せかい", accents=(1, 2))
    assert emit_unit(u) == "{[世界|せかい];1,2}"

def test_kana_content_with_accent():
    u = U(surface="それ", reading="それ", lemma="それ", lemma_reading="それ", accents=(1,))
    assert emit_unit(u) == "{それ;1}"

def test_emit_sentence_roundtrips_surface():
    units = [
        U(surface="異変", reading="いへん", lemma="異変", lemma_reading="いへん", accents=(0,)),
        U(surface="に", reading="に", is_content=False),
        U(surface="気付いた", reading="きづいた", lemma="気付く",
          lemma_reading="きづく", is_yougen=True, accents=(2,)),
    ]
    out = emit_sentence(units)
    assert out == "{[異変|いへん];0}に{[気付|きづ]いた;Y2}"
    assert markup.jrp_surface(out) == "異変に気付いた"
